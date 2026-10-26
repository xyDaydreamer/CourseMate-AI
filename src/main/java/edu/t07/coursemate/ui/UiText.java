package edu.t07.coursemate.ui;

import edu.t07.coursemate.ReplyLanguage;
import edu.t07.coursemate.ai.DeepSeekException;
import edu.t07.coursemate.rag.RetrievalResult;

final class UiText {
    private UiText() { }
    static String retrieval(RetrievalResult.Status status, ReplyLanguage l) {
        return switch (status) {
            case NO_HITS -> l.text("本轮未检索到候选课程资料。", "No candidate course material was retrieved for this turn.");
            case INSUFFICIENT -> l.text("存在候选，但相关性或上下文预算不足；未作为课程证据发送。", "Candidates exist, but relevance or context budget was insufficient; none was supplied as course evidence.");
            case READY -> l.text("本轮已重新检索并提供课程证据。", "Course evidence was retrieved again for this turn.");
            case PARTIAL -> l.text("课程证据仅覆盖问题的一部分。", "Course evidence covers only part of the question.");
            case KNOWLEDGE_UNAVAILABLE -> l.text("课程知识库不可用；这不代表课程没有相关内容。", "The course knowledge base is unavailable; this does not establish that the course lacks relevant content.");
        };
    }
    static String error(Throwable error, ReplyLanguage l) {
        if (!(error instanceof DeepSeekException e)) return l.text("操作未完成，请重试。", "The operation did not finish. Try again.");
        return switch (e.kind()) {
            case CONFIGURATION -> l.text("API 配置缺失或无效；请通过启动器设置 Key 后重启 IDEA。", "API configuration is missing or invalid. Configure the key through the launcher, then restart IDEA.");
            case NETWORK -> l.text("无法连接模型服务，请检查网络、代理或 DNS。", "Cannot connect to the model service. Check the network, proxy or DNS.");
            case TIMEOUT -> l.text("模型请求超时；没有自动重试。", "The model request timed out; it was not retried automatically.");
            case HTTP -> l.text("模型服务返回 HTTP ", "The model service returned HTTP ") + e.status()
                    + l.text("；请检查认证、额度或服务状态。", ". Check authentication, quota or service status.");
            case FORMAT -> switch (e.getMessage()) {
                case "Invalid answer JSON" -> l.text("模型回复未能解析为回答 JSON；输入已保留，未自动重试。", "The model reply could not be parsed as answer JSON. Your input was kept; no automatic retry was made.");
                case "Invalid answer schema", "Invalid answer field types" -> l.text("模型回复缺少必需字段或字段类型不符；输入已保留，未自动重试。", "The model reply has missing fields or incorrect field types. Your input was kept; no automatic retry was made.");
                default -> l.text("模型响应格式无效或未完整生成；输入已保留，没有自动重试。", "The model response was invalid or incomplete. Your input was kept; no automatic retry was made.");
            };
            case INVALID_CITATION -> l.text("回答包含格式或来源校验错误，未展示未经校验的正文。", "The answer failed format or citation validation; unchecked content was hidden.");
            case CANCELLED -> l.text("请求已停止。", "The request was stopped.");
            case INPUT_LIMIT -> l.text("输入超过预算（问题 6000、代码 16000、总上下文 28000 字符）；请缩小输入，没有静默截断。", "Input exceeds a budget (question 6,000, code 16,000, total context 28,000 characters). Shorten the input; nothing was silently truncated.");
            case UNKNOWN -> l.text("操作未完成，请检查配置后重试。", "The operation did not finish. Check configuration and try again.");
        };
    }
    static String notice(String notice, ReplyLanguage l) {
        if (l == ReplyLanguage.ZH) return switch (notice) {
            case "HISTORY_OMITTED" -> "为遵守上下文预算，较早的完整问答轮次已省略。";
            case "EVIDENCE_BUDGET" -> "为遵守上下文预算，部分完整证据片段未发送。";
            default -> notice;
        };
        return switch (notice) {
            case "HISTORY_OMITTED" -> "Older complete turns were omitted to respect the context budget.";
            case "EVIDENCE_BUDGET" -> "Some complete evidence excerpts were omitted to respect the context budget.";
            case "本地索引缓存无法读取，将重新建立。" -> "The local index cache could not be read and will be rebuilt.";
            case "无法保存本地缓存；本次仍可检索，下次启动可能需要重建。" -> "The local cache could not be saved. Retrieval remains available; rebuilding may be needed on restart.";
            case "没有发现 Lec-0 至 Lec-5 讲义 PDF，未建立课程知识库。" -> "No Lec-0 through Lec-5 lecture PDFs were found; the course knowledge base is empty.";
            default -> notice.startsWith("无法读取课程目录：") ? "Cannot read the course folder. Check its path and permissions."
                    : notice.contains("未建立该文件的索引") ? notice.substring(0, notice.indexOf('：')) + ": PDF extraction failed; this file was not indexed."
                    : notice.contains("无文字页") ? notice.substring(0, notice.indexOf('：')) + ": Some pages have no extractable text; OCR is not performed."
                    : "A knowledge-base warning was reported. Check the course files and rebuild the index.";
        };
    }
}
