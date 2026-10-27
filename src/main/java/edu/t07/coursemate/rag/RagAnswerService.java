package edu.t07.coursemate.rag;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import edu.t07.coursemate.ReplyLanguage;
import edu.t07.coursemate.ai.DeepSeekException;
import edu.t07.coursemate.ai.ModelResponse;
import edu.t07.coursemate.chat.ChatSession;
import edu.t07.coursemate.chat.AnswerResult;
import java.util.UUID;

import java.util.LinkedHashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Source metadata is always rendered from Java's retrieved chunks, never model-created filenames/pages. */
public final class RagAnswerService {
    public static final String GENERAL_NOTICE = "Response is based on general knowledge; no relevant course material was found.";
    public static final String ZH_NOTICE = "回答基于通用知识，未找到相关课程资料。";
    public static final String GENERAL_LABEL = "[通用知识补充；未找到相关课程资料]";
    // A filename is ordinary data. Reject actual source markers/attributions outside code instead.
    private static final Pattern INLINE_SOURCE = Pattern.compile("(?i)\\[S\\d+]|\\bS\\d+\\b|"
            + "[\\p{L}\\p{N}_.-]+\\.pdf\\s*(?:[-—,:：/]\\s*)?(?:pages?\\s*\\d+|第\\s*\\d+\\s*页)|"
            + "(?:来源|source|according to|参见|参考)\\s*[:：]?\\s*[\\p{L}\\p{N}_.-]+\\.pdf");
    private final CourseKnowledgeBase knowledge;
    private final AnswerGenerator generator;
    private final ModelGenerator modelGenerator;

    public RagAnswerService(CourseKnowledgeBase knowledge, AnswerGenerator generator) {
        this.knowledge = knowledge;
        this.generator = generator;
        this.modelGenerator = (q,s) -> ModelResponse.complete(generator.generate(q,s));
    }

    private RagAnswerService(CourseKnowledgeBase knowledge, ModelGenerator generator, boolean structured) {
        this.knowledge = knowledge; this.modelGenerator = generator;
        this.generator = (q,s) -> generator.generate(q,s).content();
    }
    public static RagAnswerService structured(CourseKnowledgeBase knowledge, ModelGenerator generator) {
        return new RagAnswerService(knowledge, generator, true);
    }
    @FunctionalInterface public interface ModelGenerator {
        ModelResponse generate(String question, List<CourseChunk> sources) throws DeepSeekException;
    }

    public AnswerResult ask(ChatSession.Request request) throws DeepSeekException {
        checkCancelled();
        var report = knowledge.refresh(false);
        RetrievalResult retrieval = knowledge.retrieve(request.retrievalQuery());
        JsonObject context = new JsonObject();
        context.addProperty("question", request.question());
        context.addProperty("resolved_question", request.resolvedQuestion());
        context.addProperty("topic", request.context().topic());
        context.addProperty("task", request.task().name());
        context.addProperty("context_relation", request.relation().name());
        context.addProperty("answer_language", request.language().code());
        if (!request.code().empty()) {
            context.addProperty("selected_code", request.code().text());
            context.addProperty("selected_file", request.code().fileName());
            context.addProperty("first_line", request.code().firstLine());
            context.addProperty("last_line", request.code().lastLine());
            context.addProperty("historical_code", request.historicalCode());
        }
        JsonArray history = new JsonArray();
        for (var h : request.history()) {
            JsonObject item = new JsonObject(); item.addProperty("question", h.question()); item.addProperty("answer", h.answer());
            history.add(item);
        }
        if (!history.isEmpty()) context.add("history_for_reference_only", history);
        List<String> warnings = new ArrayList<>(report.warnings()); warnings.addAll(request.notices());
        String input = context.toString();
        if (input.length() > ChatSession.MAX_TOTAL) throw new DeepSeekException(DeepSeekException.Kind.INPUT_LIMIT,
                request.language().text("总上下文超过预算，请缩小问题或代码选区。", "The total context exceeds the budget. Shorten the question or code selection."), 0, null);
        List<CourseChunk> supplied = new ArrayList<>(); int size = input.length();
        for (var c : retrieval.evidence()) {
            if (size + c.text().length() + 300 > ChatSession.MAX_TOTAL) { warnings.add("EVIDENCE_BUDGET"); continue; }
            supplied.add(c); size += c.text().length() + 300;
        }
        retrieval = new RetrievalResult(supplied.isEmpty() && !retrieval.evidence().isEmpty() ? RetrievalResult.Status.INSUFFICIENT : retrieval.status(),
                retrieval.resolvedQuery(), retrieval.candidates(), supplied, retrieval.uncoveredTopics(), retrieval.indexVersion());
        checkCancelled();
        DeepSeekException rejected = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            ModelResponse model;
            try {
                model = modelGenerator.generate(input, supplied);
            } catch (DeepSeekException exception) {
                if (!retryable(exception) || attempt == 1) throw exception;
                rejected = exception;
                continue;
            }
            checkCancelled();
            if (model.truncated()) {
                try { return parseStructured(request.id(), model, retrieval, warnings); }
                catch (DeepSeekException ignored) {
                    return new AnswerResult(request.id(), List.of(), "", List.of(), retrieval,
                            AnswerResult.Completion.TRUNCATED, model, warnings);
                }
            }
            try { return interpretComplete(request, model, retrieval, warnings); }
            catch (DeepSeekException exception) {
                if (!retryable(exception) || attempt == 1) throw exception;
                rejected = exception;
            }
        }
        throw rejected;
    }

    private static boolean retryable(DeepSeekException exception) {
        return exception.kind() == DeepSeekException.Kind.FORMAT
                || exception.kind() == DeepSeekException.Kind.INVALID_CITATION;
    }

    private AnswerResult interpretComplete(ChatSession.Request request, ModelResponse model, RetrievalResult retrieval,
                                           List<String> warnings) throws DeepSeekException {
        if (!"stop".equals(model.finishReason()) && !"unknown".equals(model.finishReason()))
            throw new DeepSeekException(DeepSeekException.Kind.FORMAT, "The model did not complete the answer.", 0, null);
        if (retrieval.evidence().isEmpty()) {
            if (hasInlineSource(model.content())) throw invalidCitation();
            return new AnswerResult(request.id(), List.of(), model.content(), List.of(), retrieval, AnswerResult.Completion.COMPLETE, model, warnings);
        }
        return parseStructured(request.id(), model, retrieval, warnings);
    }

    private static AnswerResult parseStructured(UUID turn, ModelResponse model, RetrievalResult retrieval, List<String> warnings) throws DeepSeekException {
        List<CourseChunk> supplied = retrieval.evidence();
        // Reuse the existing strict source whitelist/schema checks, independently of how the UI renders the answer.
        JsonObject root;
        try { root = parseCourseObject(model.content()); }
        catch (RuntimeException e) { throw new DeepSeekException(DeepSeekException.Kind.FORMAT, "Invalid answer JSON", 0, e); }
        if (!root.keySet().equals(Set.of("course_answer", "general_answer", "source_ids")))
            throw new DeepSeekException(DeepSeekException.Kind.FORMAT, "Invalid answer schema", 0, null);
        if (!root.get("source_ids").isJsonArray() || !root.get("general_answer").isJsonPrimitive()
                || !root.get("general_answer").getAsJsonPrimitive().isString()
                || !(root.get("course_answer").isJsonArray() || root.get("course_answer").isJsonPrimitive() && root.get("course_answer").getAsJsonPrimitive().isString()))
            throw new DeepSeekException(DeepSeekException.Kind.FORMAT, "Invalid answer field types", 0, null);
        renderGrounded(root.toString(), supplied);
        List<AnswerResult.Claim> claims = new ArrayList<>();
        JsonElement course = root.get("course_answer");
        if (course.isJsonArray()) {
            for (var e : course.getAsJsonArray()) {
                JsonObject c = e.getAsJsonObject();
                claims.add(new AnswerResult.Claim(c.get("text").getAsString(), stringIds(c.getAsJsonArray("source_ids"))));
            }
        } else if (!course.getAsString().isBlank()) claims.add(new AnswerResult.Claim(course.getAsString(), stringIds(root.getAsJsonArray("source_ids"))));
        List<AnswerResult.Source> used = new ArrayList<>();
        for (String id : stringIds(root.getAsJsonArray("source_ids"))) used.add(new AnswerResult.Source(id, supplied.get(Integer.parseInt(id.substring(1))-1)));
        return new AnswerResult(turn, claims, root.get("general_answer").getAsString(), used, retrieval, AnswerResult.Completion.COMPLETE, model, warnings);
    }
    private static JsonObject parseCourseObject(String content) {
        String json=content.strip();
        if(json.startsWith("\ufeff"))json=json.substring(1).strip();
        // Accept a single exact Markdown JSON envelope, never prose before/after it or invented citations.
        var fence=Pattern.compile("(?is)^```(?:json)?\\s*\\R(.*)\\R```$").matcher(json);
        if(fence.matches())json=fence.group(1).strip();
        JsonObject root;
        try { root = JsonParser.parseString(json).getAsJsonObject(); }
        catch (RuntimeException ignored) {
            int start = json.indexOf('{');
            int end = json.lastIndexOf('}');
            if (start < 0 || end <= start) throw ignored;
            root = JsonParser.parseString(json.substring(start, end + 1)).getAsJsonObject();
        }
        normalizeCourseObject(root);
        return root;
    }

    /** Fold common model shapes into the three-field reply. Source ids are still checked later. */
    private static void normalizeCourseObject(JsonObject root) {
        boolean idsMissing = !root.has("source_ids") || root.get("source_ids").isJsonNull();
        for (String key : List.copyOf(root.keySet())) {
            if (!key.equals("course_answer") && !key.equals("general_answer") && !key.equals("source_ids")) root.remove(key);
        }
        if (!root.has("general_answer") || root.get("general_answer").isJsonNull()) root.addProperty("general_answer", "");
        JsonArray topIds = idsMissing ? new JsonArray() : normalizeIdArray(root.get("source_ids"));
        root.add("source_ids", topIds);
        if (!root.has("course_answer") || root.get("course_answer").isJsonNull()) root.addProperty("course_answer", "");
        JsonElement course = root.get("course_answer");
        if (course.isJsonObject()) {
            JsonArray wrapped = new JsonArray();
            wrapped.add(normalizeClaim(course.getAsJsonObject(), topIds));
            root.add("course_answer", wrapped);
            course = root.get("course_answer");
        } else if (course.isJsonArray()) {
            JsonArray claims = new JsonArray();
            for (JsonElement element : course.getAsJsonArray()) {
                if (element.isJsonObject()) claims.add(normalizeClaim(element.getAsJsonObject(), topIds));
                else if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString() && !element.getAsString().isBlank()) {
                    JsonObject claim = new JsonObject();
                    claim.addProperty("text", element.getAsString());
                    claim.add("source_ids", topIds.deepCopy());
                    claims.add(claim);
                }
            }
            root.add("course_answer", claims);
            course = claims;
        }
        if (idsMissing && course.isJsonArray()) {
            JsonArray union = new JsonArray();
            Set<String> seen = new LinkedHashSet<>();
            for (JsonElement element : course.getAsJsonArray()) {
                if (!element.isJsonObject() || !element.getAsJsonObject().has("source_ids")) continue;
                for (JsonElement id : element.getAsJsonObject().getAsJsonArray("source_ids")) {
                    if (seen.add(id.getAsString())) union.add(id.getAsString());
                }
            }
            root.add("source_ids", union);
        }
    }

    private static JsonObject normalizeClaim(JsonObject claim, JsonArray fallbackIds) {
        String text = firstString(claim, "text", "content", "answer");
        JsonArray ids = claim.has("source_ids") && !claim.get("source_ids").isJsonNull()
                ? normalizeIdArray(claim.get("source_ids")) : fallbackIds.deepCopy();
        JsonObject normalized = new JsonObject();
        normalized.addProperty("text", text);
        normalized.add("source_ids", ids);
        return normalized;
    }

    private static String firstString(JsonObject object, String... names) {
        for (String name : names) {
            JsonElement value = object.get(name);
            if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) return value.getAsString();
        }
        return "";
    }

    private static JsonArray normalizeIdArray(JsonElement element) {
        JsonArray ids = new JsonArray();
        if (element == null || element.isJsonNull()) return ids;
        if (element.isJsonArray()) {
            for (JsonElement item : element.getAsJsonArray()) addCanonicalId(ids, item);
        } else addCanonicalId(ids, element);
        return ids;
    }

    private static void addCanonicalId(JsonArray ids, JsonElement item) {
        if (item == null || !item.isJsonPrimitive()) return;
        String text;
        if (item.getAsJsonPrimitive().isNumber()) text = "S" + item.getAsInt();
        else text = item.getAsString().strip();
        if (text.matches("[1-4]")) text = "S" + text;
        else if (text.matches("(?i)S[1-4]")) text = text.toUpperCase(java.util.Locale.ROOT);
        if (!text.isEmpty()) ids.add(text);
    }

    private static List<String> stringIds(JsonArray array) {
        return array.asList().stream().map(JsonElement::getAsString).distinct().toList();
    }
    private static DeepSeekException invalidCitation() {
        return new DeepSeekException(DeepSeekException.Kind.INVALID_CITATION, "The answer contains an unprovided course citation.", 0, null);
    }
    private static void checkCancelled() throws DeepSeekException {
        if (Thread.currentThread().isInterrupted()) throw new DeepSeekException(DeepSeekException.Kind.CANCELLED, "Cancelled", 0, null);
    }

    public String answer(String question) throws DeepSeekException {
        return answer(question, "");
    }

    public String answer(String question, String selectedCode) throws DeepSeekException {
        return answer(question, selectedCode, "", "");
    }

    public String answer(String question, String selectedCode, String priorQuestion, String priorAnswer)
            throws DeepSeekException {
        return answer(question, selectedCode, priorQuestion, priorAnswer, ReplyLanguage.ZH);
    }

    public String answer(String question, String selectedCode, String priorQuestion, String priorAnswer,
                         ReplyLanguage language) throws DeepSeekException {
        ReplyLanguage reply = language == null ? ReplyLanguage.ZH : language;
        if (question == null || question.isBlank()) {
            throw new DeepSeekException(reply.text("请输入问题后再发送。", "Enter a question before sending."));
        }
        CourseKnowledgeBase.Report report = knowledge.refresh(false);
        String rememberedQuestion = cleanPrior(priorQuestion, 500);
        boolean follow = !rememberedQuestion.isEmpty() && (selectedCode == null || selectedCode.isBlank()) && ChatSession.isFollowUp(question);
        List<CourseChunk> sources = knowledge.search(CodeRetrievalQuery.build(follow ? rememberedQuestion : question, selectedCode));
        String modelQuestion = composeQuestion(question, selectedCode, rememberedQuestion,
                cleanPrior(priorAnswer, 1200), reply);
        String generated = generator.generate(modelQuestion, sources);
        String answer = sources.isEmpty() ? renderGeneral(generated, reply)
                : renderGrounded(generated, sources, reply);
        if (!report.warnings().isEmpty()) {
            answer += "\n\n" + reply.text("[知识库提示]\n", "[Knowledge base]\n") + String.join("\n", report.warnings());
        }
        return answer;
    }

    private static String renderGeneral(String generated) throws DeepSeekException {
        return renderGeneral(generated, ReplyLanguage.ZH);
    }

    private static String renderGeneral(String generated, ReplyLanguage language) throws DeepSeekException {
        if (hasInlineSource(generated)) {
            throw new DeepSeekException(language.text(
                    "未检索到课程资料，但 AI 返回了来源标记；未展示该回答，请重试。未生成任何课程引用。",
                    "No course material was retrieved, but the model returned a source marker. The answer was hidden. No course citation was created."));
        }
        String notice = language == ReplyLanguage.EN ? GENERAL_NOTICE : ZH_NOTICE;
        String label = language.text(GENERAL_LABEL, "[General knowledge; no course material is cited]");
        return notice + "\n" + label + "\n" + generated;
    }

    public static String renderGrounded(String json, List<CourseChunk> sources) throws DeepSeekException {
        return renderGrounded(json, sources, ReplyLanguage.ZH);
    }

    public static String renderGrounded(String json, List<CourseChunk> sources, ReplyLanguage language)
            throws DeepSeekException {
        ReplyLanguage reply = language == null ? ReplyLanguage.ZH : language;
        try {
            JsonObject root = parseCourseObject(json);
            if (!root.keySet().equals(Set.of("course_answer", "general_answer", "source_ids"))) throw new IllegalArgumentException();
            String general = requireText(root, "general_answer");
            Set<Integer> used = requireIds(root.getAsJsonArray("source_ids"), sources.size());
            List<Claim> claims = new ArrayList<>();
            JsonElement course = root.get("course_answer");
            if (course != null && course.isJsonArray()) {
                Set<Integer> claimSources = new LinkedHashSet<>();
                for (JsonElement element : course.getAsJsonArray()) {
                    JsonObject claim = element.getAsJsonObject();
                    if (!claim.keySet().equals(Set.of("text", "source_ids"))) throw new IllegalArgumentException();
                    String text = requireText(claim, "text");
                    Set<Integer> references = requireIds(claim.getAsJsonArray("source_ids"), sources.size());
                    if (text.isBlank() || references.isEmpty()) throw new IllegalArgumentException();
                    claims.add(new Claim(text, references)); claimSources.addAll(references);
                }
                if (!used.equals(claimSources)) throw new IllegalArgumentException();
            } else {
                // Backwards-compatible three-field replies: associate the entire course paragraph with its IDs.
                String text = requireText(root, "course_answer");
                if (!text.isBlank()) claims.add(new Claim(text, used));
            }
            if (claims.isEmpty() != used.isEmpty() || (claims.isEmpty() && general.isBlank())) throw new IllegalArgumentException();
            if (hasInlineSource(general) || claims.stream().anyMatch(c -> hasInlineSource(c.text()))) throw new IllegalArgumentException();
            // A successful general answer is not a retrieval/model failure. Never list unused sources for it.
            if (used.isEmpty()) return reply.text("[通用知识补充；候选课件未被回答采用]", "[General knowledge; retrieved candidates were not used]") + "\n" + general;
            StringBuilder result = new StringBuilder();
            result.append(reply.text("[课程资料回答]\n", "[From the course materials]\n"));
            for (Claim claim : claims) {
                result.append(claim.text()).append(reply.text("\n[本段来源：", "\n[Sources for this paragraph: "));
                result.append(String.join(", ", claim.sources().stream().map(n -> "S" + n).toList())).append("]\n\n");
            }
            if (!general.isBlank()) result.append(reply.text("[通用知识补充；不由课件证明]\n", "[General knowledge; not established by the lecture]\n")).append(general).append("\n\n");
            result.append(reply.text("[已校验来源文件与页码；结论未经自动事实验证]\n", "[Source identity and page checked; claims are not automatically fact-checked]\n"));
            for (int number : used) {
                CourseChunk chunk = sources.get(number - 1);
                result.append("[S").append(number).append("] ").append(chunk.fileName())
                        .append(pageLabel(reply, chunk.page())).append("\n")
                        .append(reply.text("[课件原文片段；请人工核对本段结论]\n", "[Original lecture excerpt; check the paragraph against this text]\n"))
                        .append(literalExcerpt(chunk.text())).append("\n\n");
            }
            result.append(reply.text("\n[本次实际检索到的页面；不代表每页都支持回答]\n",
                    "\n[Pages retrieved for this question; not every page supports the answer]\n"));
            for (int i = 0; i < sources.size(); i++) {
                CourseChunk chunk = sources.get(i);
                result.append("S").append(i + 1).append(": ").append(chunk.fileName())
                        .append(pageLabel(reply, chunk.page())).append('\n');
            }
            return result.toString();
        } catch (RuntimeException ignored) {
            throw new DeepSeekException(DeepSeekException.Kind.INVALID_CITATION, reply.text(
                    "AI 返回的课程回答格式或来源不合法，未展示未经核对的回答；请重试或缩小问题范围。",
                    "The course answer format or source was invalid, so the unchecked answer was hidden. Retry or narrow the question."), 0, ignored);
        }
    }

    private static String pageLabel(ReplyLanguage language, int page) {
        return language.text(" — PDF 第 " + page + " 页", " — PDF page " + page);
    }

    private static String composeQuestion(String question, String selectedCode, String priorQuestion,
                                          String priorAnswer, ReplyLanguage language) {
        boolean hasCode = selectedCode != null && !selectedCode.isBlank();
        boolean hasPrior = !priorQuestion.isEmpty() || !priorAnswer.isEmpty();
        if (!hasCode && !hasPrior && language != ReplyLanguage.EN) return question;
        JsonObject context = new JsonObject();
        context.addProperty("question", question);
        if (language == ReplyLanguage.EN) context.addProperty("answer_language", language.code());
        if (hasCode) context.addProperty("selected_code", selectedCode);
        if (!priorQuestion.isEmpty()) context.addProperty("prior_question", priorQuestion);
        if (!priorAnswer.isEmpty()) context.addProperty("prior_answer", priorAnswer);
        return context.toString();
    }

    private static String literalExcerpt(String text) {
        int length = 3;
        var backticks = Pattern.compile("`{3,}").matcher(text);
        while (backticks.find()) length = Math.max(length, backticks.group().length() + 1);
        String fence = "`".repeat(length);
        return fence + "text\n" + text + "\n" + fence;
    }

    /** Conservative full-query cues: a new topic such as dinner must not inherit lecture evidence. */
    private static boolean isContextualFollowUp(String question) {
        String q = question.strip().toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[\\p{P}]+", " ").replaceAll("\\s+", " ").strip();
        if (q.matches("(?:please )?(?:(?:can|could|would) you )?(?:give|show)(?: me)? "
                + "(?:(?:one|an?|another|more|some) )*(?:examples?|details?)(?: (?:of|for) (?:that|this|it|the above))?"
                + "|(?:please )?(?:explain|clarify|elaborate on) (?:that|this|it|the above)(?: (?:again|in more detail|further))?"
                + "|why(?: is that| does that happen)?|(?:please )?(?:continue|go on)")) return true;
        q = q.replace(" ", "");
        return q.matches("(?:请)?(?:(?:再|继续|进一步)?(?:举|给|给我|提供)(?:一个|一些|更多|个)?(?:例子|示例)"
                + "|(?:再|继续|进一步)?(?:解释|说明|展开|详细说明|详细解释)(?:一下|一点|一些)?(?:吗|呢)?"
                + "|为什么(?:会)?这样|这是为什么|为什么|继续"
                + "|(?:这个|那个|它|上面(?:的内容)?)(?:是什么意思|为什么|怎么理解|能举例吗))");
    }

    /** Keep prior prose/code, but stop before program-rendered sources, excerpts and index details. */
    private static String cleanPrior(String text, int limit) {
        if (text == null || text.isBlank()) return "";
        StringBuilder kept = new StringBuilder();
        for (String line : text.split("\n")) {
            String trimmed = line.strip();
            if (trimmed.startsWith("[已校验来源") || trimmed.startsWith("[Source identity")
                    || trimmed.startsWith("[Java 核对") || trimmed.startsWith("[本次实际检索")
                    || trimmed.startsWith("[知识库提示]") || trimmed.startsWith("[Course sources")
                    || trimmed.startsWith("[Pages retrieved") || trimmed.startsWith("[Knowledge base]")
                    || trimmed.startsWith("[课件原文片段") || trimmed.startsWith("[Original lecture excerpt")) break;
            if (trimmed.isEmpty() || INLINE_SOURCE.matcher(trimmed).find()
                    || trimmed.startsWith("[本段来源：") || trimmed.startsWith("[Sources for this paragraph:")) continue;
            if (!kept.isEmpty()) kept.append('\n');
            kept.append(line.stripTrailing());
        }
        String value = kept.toString().strip();
        if (value.length() <= limit) return value;
        int end = limit;
        if (Character.isLowSurrogate(value.charAt(end))) end--;
        return value.substring(0, end);
    }

    private static Set<Integer> requireIds(JsonArray ids, int count) {
        if (ids == null) throw new IllegalArgumentException();
        Set<Integer> result = new LinkedHashSet<>();
        for (JsonElement id : ids) {
            if (!id.isJsonPrimitive() || !id.getAsJsonPrimitive().isString()
                    || !id.getAsString().matches("S[1-4]")) throw new IllegalArgumentException();
            int number = Integer.parseInt(id.getAsString().substring(1));
            if (number > count) throw new IllegalArgumentException();
            result.add(number);
        }
        return result;
    }

    private static boolean hasInlineSource(String text) {
        String prose = text.replaceAll("(?s)```.*?```|`[^`\\r\\n]*`", " ");
        return INLINE_SOURCE.matcher(prose).find();
    }

    private record Claim(String text, Set<Integer> sources) { }

    private static String requireText(JsonObject object, String name) {
        JsonElement value = object.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new IllegalArgumentException();
        return value.getAsString();
    }

    @FunctionalInterface
    public interface AnswerGenerator {
        String generate(String question, List<CourseChunk> sources) throws DeepSeekException;
    }
}
