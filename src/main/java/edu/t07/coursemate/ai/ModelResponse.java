package edu.t07.coursemate.ai;

/** Transport metadata never becomes part of the model's content or JSON document. */
public record ModelResponse(String content, String finishReason, Usage usage, String requestId) {
    public ModelResponse {
        if (content == null) content = "";
        if (finishReason == null) finishReason = "unknown";
        if (usage == null) usage = new Usage(0, 0, 0);
        if (requestId == null) requestId = "";
    }
    public boolean truncated() { return "length".equals(finishReason); }
    public static ModelResponse complete(String text) { return new ModelResponse(text, "stop", null, ""); }
    public record Usage(long inputTokens, long outputTokens, long totalTokens) { }
}
