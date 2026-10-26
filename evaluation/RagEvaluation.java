import com.google.gson.*;
import edu.t07.coursemate.rag.*;
import edu.t07.coursemate.ai.DeepSeekException;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Offline retrieval/contract evaluation only. The scripted answer is NOT answer-quality evidence. */
public class RagEvaluation {
    public static void main(String[] args) throws Exception {
        if (args.length != 4) throw new IllegalArgumentException("courseDir questions.json output.json label");
        Path directory = Path.of(args[0]), output = Path.of(args[2]);
        Path scratch = Files.createTempDirectory("coursemate-evaluation-");
        try {
            var knowledge = new CourseKnowledgeBase(directory, scratch.resolve("index.json"), new PdfCourseReader());
            long started = System.nanoTime();
            var report = knowledge.refresh(false);
            double importMs = elapsed(started);
            if (report.documents().size() != 9 || !report.warnings().isEmpty())
                throw new IllegalStateException("Evaluation requires all nine unchanged teacher lectures: " + report.summary());
            var cases = JsonParser.parseString(Files.readString(Path.of(args[1]))).getAsJsonArray();
            JsonArray results = new JsonArray(), corpus = new JsonArray(), indexDocuments = new JsonArray();
            for (var file : report.documents()) {
                var entry = new JsonObject(); entry.addProperty("file", file.name());
                entry.addProperty("sha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest(Files.readAllBytes(directory.resolve(file.name())))));
                corpus.add(entry);
                JsonObject metadata = new JsonObject(); metadata.addProperty("file", file.name());
                metadata.addProperty("pages", file.pages()); metadata.addProperty("chunks", file.chunks().size());
                indexDocuments.add(metadata);
            }
            int positives = 0, hits = 0, negatives = 0, correctlyEmpty = 0, contracts = 0, contractPasses = 0;
            for (var element : cases) {
                var item = element.getAsJsonObject(); var result = item.deepCopy();
                String kind = item.get("kind").getAsString();
                started = System.nanoTime();
                if (kind.equals("retrieval")) {
                    List<CourseChunk> captured = new ArrayList<>();
                    new RagAnswerService(knowledge, (question, sources) -> {
                        captured.addAll(sources);
                        return sources.isEmpty() ? "Scripted answer; not evaluated."
                                : "{\"course_answer\":\"Scripted answer; not evaluated.\",\"general_answer\":\"\",\"source_ids\":[\"S1\"]}";
                    }).answer(item.get("question").getAsString(), item.has("code") ? item.get("code").getAsString() : "");
                    List<String> top3 = captured.stream().limit(3).map(c -> c.fileName() + ":" + c.page()).toList();
                    result.add("actualTop3", new Gson().toJsonTree(top3));
                    Set<String> expected = new HashSet<>(); item.getAsJsonArray("expected").forEach(e -> expected.add(e.getAsString()));
                    boolean passed;
                    if (expected.isEmpty()) { negatives++; passed = captured.isEmpty(); if (passed) correctlyEmpty++; }
                    else { positives++; passed = top3.stream().anyMatch(expected::contains); if (passed) hits++; }
                    result.addProperty("retrievalPass", passed);
                } else {
                    contracts++; boolean accepted = true;
                    try {
                        if (kind.equals("generalCitation")) {
                            var empty = new CourseKnowledgeBase(scratch, scratch.resolve("empty.json"), new PdfCourseReader());
                            new RagAnswerService(empty, (q, sources) -> item.get("response").getAsString()).answer("astronomyzz");
                        } else RagAnswerService.renderGrounded(item.get("response").getAsString(),
                                List.of(new CourseChunk("Lec-3.2-Generics-and-Collections.pdf", 15, "Map keys are unique.\nUse merge to count.")));
                    } catch (DeepSeekException e) { accepted = false; }
                    boolean pass = accepted == item.get("accepted").getAsBoolean(); if (pass) contractPasses++;
                    result.addProperty("actualAccepted", accepted); result.addProperty("contractPass", pass);
                }
                result.addProperty("elapsedMsSingleRun", elapsed(started));
                result.addProperty("realApiAnswerCorrectness", "NOT_RUN");
                result.addProperty("realApiCitationAccuracy", "NOT_RUN");
                results.add(result);
            }
            JsonObject root = new JsonObject(); root.addProperty("label", args[3]);
            root.addProperty("timestamp", java.time.Instant.now().toString());
            root.addProperty("mode", "OFFLINE_RETRIEVAL_AND_SCRIPTED_CONTRACT_ONLY");
            root.addProperty("questionsSha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(Path.of(args[1])))));
            root.add("corpus", corpus); root.addProperty("chunks", report.chunkCount());
            root.add("indexDocuments", indexDocuments);
            root.addProperty("coldImportMsSingleRun", importMs); root.addProperty("positiveQuestions", positives);
            root.addProperty("hitAt3Count", hits); root.addProperty("hitAt3", (double) hits / positives);
            root.addProperty("negativeQuestions", negatives); root.addProperty("correctlyEmptyCount", correctlyEmpty);
            root.addProperty("contractCases", contracts); root.addProperty("contractPasses", contractPasses);
            root.addProperty("paidApiCalls", 0); root.add("results", results);
            Files.createDirectories(output.toAbsolutePath().getParent());
            Files.writeString(output, new GsonBuilder().setPrettyPrinting().create().toJson(root), StandardCharsets.UTF_8);
            System.out.printf(Locale.ROOT, "%s Hit@3 %d/%d; no-source %d/%d; contracts %d/%d; API calls 0%n",
                    args[3], hits, positives, correctlyEmpty, negatives, contractPasses, contracts);
        } finally {
            try (var paths = Files.walk(scratch)) { for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(p); }
        }
    }
    private static double elapsed(long start) { return (System.nanoTime() - start) / 1_000_000.0; }
}
