import com.google.gson.*;
import edu.t07.coursemate.rag.*;
import edu.t07.coursemate.ai.ModelResponse;
import edu.t07.coursemate.chat.ChatSession;
import edu.t07.coursemate.ReplyLanguage;
import java.nio.file.*;
import java.util.*;

/** Offline ranking inspection; reports metadata/scores, never calls a model. */
public class RetrievalDiagnostics {
    public static void main(String[] args) throws Exception {
        Path scratch = Path.of(args[2]);
        Files.createDirectories(scratch);
        var kb = new CourseKnowledgeBase(Path.of(args[0]), scratch.resolve("index.json"), new PdfCourseReader());
        var report = kb.refresh(false);
        var service = RagAnswerService.structured(kb, (q,s) -> ModelResponse.complete(s.isEmpty()
                ? "Offline general fixture."
                : "{\"course_answer\":\"Offline fixture, not a real model answer.\",\"general_answer\":\"\",\"source_ids\":[\"S1\"]}"));
        var cases = JsonParser.parseString(Files.readString(Path.of(args[1]))).getAsJsonArray();
        JsonArray results = new JsonArray();
        for (var element : cases) {
            var item = element.getAsJsonObject();
            if (item.has("kind")) continue;
            String question = item.get("question").getAsString();
            var plan = QueryPlan.understand(question);
            var result = kb.retrieve(question);
            JsonObject row = item.deepCopy();
            row.add("queryPlan", new Gson().toJsonTree(plan));
            row.add("candidates", new Gson().toJsonTree(result.candidates().stream().map(h -> Map.of(
                    "page", h.chunk().fileName() + ":" + h.chunk().page(),
                    "chunkId", h.chunk().chunkId(), "bm25", h.bm25(), "score", h.score(), "coverage", h.coverage())).toList()));
            row.add("evidence", new Gson().toJsonTree(result.evidence().stream().map(c -> c.fileName() + ":" + c.page()).toList()));
            var answer = service.ask(new ChatSession().prepare(question, null, true, ReplyLanguage.EN));
            row.addProperty("retrievalStatus", result.status().name());
            row.addProperty("mode", "OFFLINE_REAL_PDF_AND_SCRIPTED_ANSWER_NOT_LIVE_PLUGIN");
            row.add("fixtureUsedSourceIds", new Gson().toJsonTree(answer.sources().stream().map(s -> s.id()).toList()));
            List<String> expectedPages = item.getAsJsonArray("expected").asList().stream().map(JsonElement::getAsString).toList();
            List<String> candidatePages = result.candidates().stream().map(h -> h.chunk().fileName() + ":" + h.chunk().page()).distinct().toList();
            List<String> evidencePages = result.evidence().stream().map(c -> c.fileName() + ":" + c.page()).distinct().toList();
            int candidateRank = candidatePages.stream().filter(expectedPages::contains).mapToInt(p -> candidatePages.indexOf(p) + 1).min().orElse(-1);
            int evidenceRank = evidencePages.stream().filter(expectedPages::contains).mapToInt(p -> evidencePages.indexOf(p) + 1).min().orElse(-1);
            row.addProperty("expectedCandidatePageRank", candidateRank);
            row.addProperty("expectedEvidencePageRank", evidenceRank);
            row.addProperty("pass", expectedPages.isEmpty() ? evidencePages.isEmpty() && answer.sources().isEmpty() : evidenceRank > 0 && evidenceRank <= 3);
            // Distinguish absence from recall from being ranked below the bounded candidate list.
            JsonArray expected = new JsonArray();
            for (var label : item.getAsJsonArray("expected")) {
                String page = label.getAsString();
                for (var c : report.documents().stream().flatMap(d -> d.chunks().stream()).toList()) {
                    if (!(c.fileName() + ":" + c.page()).equals(page)) continue;
                    JsonObject target = new JsonObject();
                    target.addProperty("page", page);
                    target.addProperty("chunkId", c.chunkId());
                    target.addProperty("topicTermsPresent", plan.terms().stream().filter(t ->
                            java.util.regex.Pattern.compile("(?i)\\b" + java.util.regex.Pattern.quote(t) + "\\b").matcher(c.text()).find()).count());
                    target.addProperty("targetAloneAdmitted", !new KeywordIndex(List.of(c)).search(question).isEmpty());
                    expected.add(target);
                }
            }
            row.add("expectedDiagnostics", expected);
            results.add(row);
        }
        Files.writeString(scratch.resolve("diagnostics.json"), new GsonBuilder().setPrettyPrinting().create().toJson(results));
        System.out.println("Offline diagnostics saved: " + results.size() + " questions.");
    }
}
