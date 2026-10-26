package edu.t07.coursemate.rag;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import edu.t07.coursemate.ReplyLanguage;
import edu.t07.coursemate.ai.DeepSeekException;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Source metadata is always rendered from Java's retrieved chunks, never model-created filenames/pages. */
public final class RagAnswerService {
    public static final String GENERAL_NOTICE = "Response is based on general knowledge; no relevant course material was found.";
    public static final String ZH_NOTICE = "回答基于通用知识，未找到相关课程资料。";
    public static final String GENERAL_LABEL = "[通用知识补充；未找到相关课程资料]";
    private static final Pattern INLINE_SOURCE = Pattern.compile("(?i)\\.pdf|第\\s*\\d+\\s*页|\\bpages?\\s*\\d+|\\[S\\d+]");
    private final CourseKnowledgeBase knowledge;
    private final AnswerGenerator generator;

    public RagAnswerService(CourseKnowledgeBase knowledge, AnswerGenerator generator) {
        this.knowledge = knowledge;
        this.generator = generator;
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
        // Retrieve by the student's current task, not incidental names in source code or the previous answer.
        List<CourseChunk> sources = knowledge.search(question);
        String rememberedQuestion = cleanPrior(priorQuestion, 500);
        if (sources.isEmpty() && !rememberedQuestion.isEmpty()) {
            sources = knowledge.search(rememberedQuestion + "\n" + question);
        }
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
        if (INLINE_SOURCE.matcher(generated).find()) {
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
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            String course = requireText(root, "course_answer");
            String general = requireText(root, "general_answer");
            JsonArray ids = root.getAsJsonArray("source_ids");
            if (ids == null || (course.isBlank() && general.isBlank())) throw new IllegalArgumentException();
            Set<Integer> used = new LinkedHashSet<>();
            for (JsonElement id : ids) {
                if (!id.isJsonPrimitive() || !id.getAsJsonPrimitive().isString()
                        || !id.getAsString().matches("S[1-4]")) throw new IllegalArgumentException();
                int number = Integer.parseInt(id.getAsString().substring(1));
                if (number > sources.size()) throw new IllegalArgumentException();
                used.add(number);
            }
            if (course.isBlank() != used.isEmpty()) throw new IllegalArgumentException();
            // The model is asked for prose only. Reject inline file/page citations rather than displaying invented ones.
            if (INLINE_SOURCE.matcher(course + "\n" + general).find()) throw new IllegalArgumentException();
            // A successful general answer is not a retrieval/model failure. Never list unused sources for it.
            if (used.isEmpty()) return renderGeneral(general, reply);
            StringBuilder result = new StringBuilder();
            if (!course.isBlank()) {
                result.append(reply.text("[课程资料回答]\n", "[From the course materials]\n")).append(course).append("\n\n");
            }
            if (!general.isBlank()) {
                result.append(reply.text("[通用知识补充；不由课件证明]\n", "[General knowledge; not established by the lecture]\n"))
                        .append(general).append("\n\n");
            }
            result.append(reply.text("[Java 核对的课程来源：文件名 / PDF 页码（从 1 开始）]\n",
                    "[Course sources verified in Java: file name / PDF page, starting at 1]\n"));
            for (int number : used) {
                CourseChunk chunk = sources.get(number - 1);
                result.append("[S").append(number).append("] ").append(chunk.fileName())
                        .append(pageLabel(reply, chunk.page())).append('\n');
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
            throw new DeepSeekException(reply.text(
                    "AI 返回的课程回答格式或来源不合法，未展示未经核对的回答；请重试或缩小问题范围。",
                    "The course answer format or source was invalid, so the unchecked answer was hidden. Retry or narrow the question."));
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

    /** Drop citation lines so the model is not tempted to copy filenames or page numbers. */
    private static String cleanPrior(String text, int limit) {
        if (text == null || text.isBlank()) return "";
        StringBuilder kept = new StringBuilder();
        for (String line : text.split("\n")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || INLINE_SOURCE.matcher(trimmed).find()
                    || trimmed.startsWith("[Java 核对") || trimmed.startsWith("[本次实际检索")
                    || trimmed.startsWith("[知识库提示]") || trimmed.startsWith("[Course sources")
                    || trimmed.startsWith("[Pages retrieved") || trimmed.startsWith("[Knowledge base]")) continue;
            if (!kept.isEmpty()) kept.append('\n');
            kept.append(trimmed);
        }
        String value = kept.toString().strip();
        if (value.length() <= limit) return value;
        int end = limit;
        if (Character.isLowSurrogate(value.charAt(end))) end--;
        return value.substring(0, end);
    }

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
