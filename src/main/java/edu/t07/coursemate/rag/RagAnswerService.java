package edu.t07.coursemate.rag;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import edu.t07.coursemate.ai.DeepSeekException;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Source metadata is always rendered from Java's retrieved chunks, never model-created filenames/pages. */
public final class RagAnswerService {
    public static final String GENERAL_NOTICE = "Response is based on general knowledge; no relevant course material was found.";
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
        if (question == null || question.isBlank()) throw new DeepSeekException("请输入问题后再发送。");
        CourseKnowledgeBase.Report report = knowledge.refresh(false);
        // Retrieve by the student's task, not incidental variable names or comments in source code.
        List<CourseChunk> sources = knowledge.search(question);
        String modelQuestion = question;
        if (selectedCode != null && !selectedCode.isBlank()) {
            JsonObject context = new JsonObject();
            context.addProperty("question", question);
            context.addProperty("selected_code", selectedCode);
            modelQuestion = context.toString();
        }
        String generated = generator.generate(modelQuestion, sources);
        String answer = sources.isEmpty() ? renderGeneral(generated)
                : renderGrounded(generated, sources);
        if (!report.warnings().isEmpty()) answer += "\n\n[知识库提示]\n" + String.join("\n", report.warnings());
        return answer;
    }

    private static String renderGeneral(String generated) throws DeepSeekException {
        if (INLINE_SOURCE.matcher(generated).find()) {
            throw new DeepSeekException("未检索到课程资料，但 AI 返回了来源标记；未展示该回答，请重试。未生成任何课程引用。");
        }
        return GENERAL_NOTICE + "\n" + GENERAL_LABEL + "\n" + generated;
    }

    public static String renderGrounded(String json, List<CourseChunk> sources) throws DeepSeekException {
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
            if (used.isEmpty()) return renderGeneral(general);
            StringBuilder result = new StringBuilder();
            if (!course.isBlank()) result.append("[课程资料回答]\n").append(course).append("\n\n");
            if (!general.isBlank()) result.append("[通用知识补充；不由课件证明]\n").append(general).append("\n\n");
            result.append("[Java 核对的课程来源：文件名 / PDF 页码（从 1 开始）]\n");
            for (int number : used) {
                CourseChunk chunk = sources.get(number - 1);
                result.append("[S").append(number).append("] ").append(chunk.fileName())
                        .append(" — PDF 第 ").append(chunk.page()).append(" 页\n");
            }
            result.append("\n[本次实际检索到的页面；不代表每页都支持回答]\n");
            for (int i = 0; i < sources.size(); i++) {
                CourseChunk chunk = sources.get(i);
                result.append("S").append(i + 1).append(": ").append(chunk.fileName())
                        .append(" — PDF 第 ").append(chunk.page()).append(" 页\n");
            }
            return result.toString();
        } catch (RuntimeException ignored) {
            throw new DeepSeekException("AI 返回的课程回答格式或来源不合法，未展示未经核对的回答；请重试或缩小问题范围。");
        }
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
