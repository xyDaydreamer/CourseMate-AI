package edu.t07.coursemate.chat;

import edu.t07.coursemate.ReplyLanguage;
import edu.t07.coursemate.ai.DeepSeekException;
import edu.t07.coursemate.rag.CodeRetrievalQuery;
import edu.t07.coursemate.rag.QueryPlan;
import java.util.*;

/** Project-panel scoped state. UI owns mutation; workers receive immutable request snapshots. */
public final class ChatSession {
    public enum Task { CONCEPT, CODE_EXPLANATION, DEBUGGING, EXAMPLE_EXTENSION, CODE_REVISION }
    public enum Relation { NEW_TOPIC, NEW_SELECTION, CONTINUATION, CORRECTION }
    public record Context(String topic, String resolvedQuestion, Task task, Relation relation) { }
    public record Code(String text, String fileName, int firstLine, int lastLine, long revision, List<String> features) {
        public Code { text = text == null ? "" : text; fileName = fileName == null ? "" : fileName; features = List.copyOf(features); }
        public static Code of(String text) { return new Code(text, "", 0, 0, 0, List.of()); }
        public boolean empty() { return text.isBlank(); }
    }
    public record History(String question, String answer) { }
    public record Request(UUID id, long epoch, String question, Context context,
                          Code code, boolean historicalCode, ReplyLanguage language, List<History> history,
                          List<String> notices) {
        public Request { history = List.copyOf(history); notices = List.copyOf(notices); }
        public String resolvedQuestion() { return context.resolvedQuestion(); }
        public Task task() { return context.task(); }
        public Relation relation() { return context.relation(); }
        public String retrievalQuery() {
            String retrievalQuestion = relation() == Relation.CONTINUATION ? resolvedQuestion() + "\n" + question : resolvedQuestion();
            String result = CodeRetrievalQuery.build(retrievalQuestion, code.text());
            if (!code.empty() && !QueryPlan.hasCourseTopic(result) && code.features().isEmpty()) return "";
            if (!QueryPlan.understand(resolvedQuestion()).outsideDomain() && !code.features().isEmpty()
                    && (task() != Task.CONCEPT || QueryPlan.hasCourseTopic(resolvedQuestion()))) result += " " + String.join(" ", code.features());
            return result;
        }
    }
    public static final int MAX_QUESTION = 6000, MAX_CODE = 16000, MAX_TOTAL = 28000, MAX_HISTORY = 3;
    private Context context = new Context("", "", Task.CONCEPT, Relation.NEW_TOPIC);
    private Code rememberedCode = Code.of("");
    private final Deque<History> history = new ArrayDeque<>();
    private UUID pending;
    private long epoch;

    public Request prepare(String question, Code fresh, boolean allowHistoricalCode, ReplyLanguage language) throws DeepSeekException {
        String q = question == null ? "" : question.strip();
        if (q.isEmpty() || q.length() > MAX_QUESTION) throw limit("问题为空或超过 6000 字符。", "The question is empty or exceeds 6,000 characters.", language);
        boolean hasNew = fresh != null && !fresh.empty();
        Task requestedTask = explicitTask(q);
        String correction = correctionFocus(q);
        boolean corrected = !hasNew && !context.topic().isEmpty() && !explicitReset(q) && !correction.isEmpty();
        boolean relatedCorrection = corrected && relatedToContext(correction);
        boolean follow = !hasNew && !context.topic().isEmpty() && !corrected && !explicitReset(q) && continuesContext(q);
        Relation relation = hasNew ? Relation.NEW_SELECTION : corrected ? Relation.CORRECTION : follow ? Relation.CONTINUATION : Relation.NEW_TOPIC;
        boolean inherited = (follow || relatedCorrection) && allowHistoricalCode && !rememberedCode.empty();
        Code code = hasNew ? fresh : inherited ? rememberedCode : Code.of("");
        if (code.text().length() > MAX_CODE) throw limit("选中代码超过 16000 字符，请缩小选区；没有发送或静默截断。", "Selected code exceeds 16,000 characters. Select less code; nothing was sent or silently truncated.", language);
        String topic = follow || relatedCorrection ? context.topic() : corrected ? correction : q;
        String resolved = follow ? context.resolvedQuestion() : relatedCorrection ? topic + "\n" + correction : corrected ? correction : q;
        Task task = requestedTask != null ? requestedTask : !code.empty() ? Task.CODE_EXPLANATION
                : follow && context.task() == Task.DEBUGGING ? Task.DEBUGGING : Task.CONCEPT;
        List<History> kept = new ArrayList<>(); List<String> notices = new ArrayList<>();
        int size = q.length() + resolved.length() + code.text().length();
        if (follow) {
            List<History> previous = new ArrayList<>(history);
            for (int i = previous.size() - 1; i >= 0; i--) {
                History h = previous.get(i);
                if (size + h.question.length() + h.answer.length() > MAX_TOTAL) {
                    notices.add("HISTORY_OMITTED"); break;
                }
                kept.addFirst(h); size += h.question.length() + h.answer.length();
            }
        }
        UUID id = UUID.randomUUID(); pending = id;
        return new Request(id, epoch, q, new Context(topic, resolved, task, relation), code, inherited, language, kept, notices);
    }
    public boolean current(Request request) { return request.epoch == epoch && request.id.equals(pending); }
    public boolean commit(Request request, String answer) {
        if (!current(request)) return false;
        // New subjects/selections and explicit corrections start a clean history boundary.
        if (request.relation() != Relation.CONTINUATION) history.clear();
        context = request.context(); rememberedCode = request.code;
        history.addLast(new History(request.question, answer));
        while (history.size() > MAX_HISTORY) history.removeFirst();
        pending = null; return true;
    }
    public void cancel() { pending = null; epoch++; }
    public void clear() { cancel(); context = new Context("", "", Task.CONCEPT, Relation.NEW_TOPIC); rememberedCode = Code.of(""); history.clear(); }
    public void forgetCode() { rememberedCode = Code.of(""); }
    public String topic() { return context.topic(); }
    public Code rememberedCode() { return rememberedCode; }
    public boolean willUseHistoricalCode(String question) {
        if (rememberedCode.empty() || explicitReset(question)) return false;
        String correction = correctionFocus(question);
        return correction.isEmpty() ? continuesContext(question) : relatedToContext(correction);
    }

    private boolean continuesContext(String question) {
        // Compare related concepts with features of the actual selected snapshot, not just "Explain this code".
        String reference = CodeRetrievalQuery.build(context.topic() + "\n" + context.resolvedQuestion(), rememberedCode.text());
        return isFollowUp(question, reference);
    }
    private boolean relatedToContext(String question) {
        var next=QueryPlan.understand(question);
        if(next.outsideDomain())return false;
        String reference=CodeRetrievalQuery.build(context.topic()+"\n"+context.resolvedQuestion(),rememberedCode.text());
        var old=QueryPlan.understand(reference);
        var oldTopics=QueryPlan.understand(reference+" "+String.join(" ",old.terms())).topics().keySet();
        return next.topics().isEmpty()||!Collections.disjoint(next.topics().keySet(),oldTopics);
    }
    private static Task explicitTask(String question) {
        String q=question.toLowerCase(Locale.ROOT);
        if(q.matches("(?s).*(?:\\b(?:fix(?:ed)?|repair|correct(?:ed)?|rewrite|refactor|revised)\\b|修改|修复|改正|更正|重构|优化|怎么改|如何改).*") )return Task.CODE_REVISION;
        if(q.matches("(?s).*(?:\\b(?:bug|debug|error|stack trace)\\b|报错|错误|调试|无法编译|不能编译).*") )return Task.DEBUGGING;
        if(q.matches("(?s).*(?:(?:\\b(?:give|show|provide|another|more)\\b).*\\bexamples?\\b|举.*(?:例子|示例)|举例).*") )return Task.EXAMPLE_EXTENSION;
        if(!CodeRetrievalQuery.isGenericCodeTask(question)&&QueryPlan.hasCourseTopic(question))return Task.CONCEPT;
        return null;
    }
    private static boolean explicitReset(String question) {
        return question.matches("(?is).*(?:换个话题|换一个话题|新的问题|另一个问题|不谈(?:这个|上面)|\\b(?:new (?:topic|question)|change (?:the )?subject|unrelated question)\\b).*");
    }
    private static String correctionFocus(String question) {
        var marker=java.util.regex.Pattern.compile("(?is)(?:我(?:真正)?(?:问|说|指)(?:的)?(?:是)?|我的意思是|准确地说|更正为|改用|\\bi (?:mean|meant)\\b|\\bto clarify\\b|\\bactually\\b)[，,:：\\s]*(.+)").matcher(question);
        String focus="";
        if(marker.find())focus=marker.group(1);
        else {
            var contrast=java.util.regex.Pattern.compile("(?is)(?:不是|不要|\\bnot\\b).*(?:而是|改用|\\bbut(?: rather)?\\b)[，,:：\\s]*(.+)").matcher(question);
            if(contrast.find())focus=contrast.group(1);
        }
        return focus.replaceFirst("(?is)[，,;；]?\\s*(?:而?不是|\\bnot\\b).*$", "").replaceAll("[。.!?？！，,;；\\s]+$", "").strip();
    }

    private static boolean isFollowUp(String question,String topic) {
        if (isFollowUp(question)) return true;
        if (QueryPlan.understand(question).outsideDomain()) return false;
        var newTopics=QueryPlan.understand(question).topics().keySet();
        var oldPlan=QueryPlan.understand(topic);
        var oldTopics=QueryPlan.understand(topic+" "+String.join(" ",oldPlan.terms())).topics().keySet();
        if(!newTopics.isEmpty()&&Collections.disjoint(newTopics,oldTopics))return false;
        if(hasContextReference(question))return true;
        String[] clauses=question.split("(?i)[，,。;；]|\\band\\b|并且|并|同时",2);
        if (clauses.length!=2||!isFollowUp(clauses[0].strip())) return false;
        String tail=clauses[1].strip().toLowerCase(Locale.ROOT);
        return tail.matches("(?s).*(?:说明|解释|指出|比较|实际|这个|其中|调用|输出|explain|show|indicate|which|called|output|that|this).*" );
    }
    private static boolean hasContextReference(String question) {
        String q=question.toLowerCase(Locale.ROOT);
        // Relational cues describe a dependency on earlier content; unrelated named topics were rejected above.
        boolean chinesePointer=q.matches("(?s).*(?:上(?:一轮|面|文)|刚才|前面|之前|这(?:段|个|里|样)|那|它|其中|此处|该(?:代码|方法|变量|例子)).*");
        boolean chineseAction=q.matches("(?s).*(?:怎么|如何|为什么|是否|会不会|影响|改|修复|优化|重构|说明|解释|写|结果|输出).*");
        boolean revisedArtifact=q.matches("(?s).*(?:修改|修复|改正|更正|调整|优化|重构)(?:之后|以后|后|完|版本|版).*")
                ||q.matches("(?s).*(?:完整|正确|修正)(?:的)?(?:修改)?(?:版本|写法|代码).*");
        boolean genericRepair=q.replaceAll("[\\p{P}\\s]", "").matches("(?:请|麻烦|可以|能|帮我|能不能|可不可以)*(?:修复|修改|改|优化|重构)(?:一下|好|这段代码)?(?:吗|吧|呢)?");
        boolean englishPointer=q.matches("(?s).*\\b(?:it|that|this|above|previous|earlier|same|corrected|fixed|revised)\\b.*");
        boolean englishAction=q.matches("(?s).*\\b(?:fix|correct|change|look|show|rewrite|modify|improve|work|happen|output|result|version|code|example|why|how|could|would|can)\\b.*");
        return revisedArtifact||genericRepair||chinesePointer&&chineseAction||englishPointer&&englishAction;
    }
    public static boolean isFollowUp(String question) {
        String q = question.toLowerCase(Locale.ROOT).replaceAll("[\\p{P}]", " ").replaceAll("\\s+", " ").strip();
        if (QueryPlan.understand(q).outsideDomain() || QueryPlan.hasCourseTopic(q)) return false;
        return q.matches("(?:please )?(?:(?:can|could|would) you )?(?:give|show)(?: me)? (?:(?:one|an?|another|more|some) )*(?:examples?|details?)(?: (?:of|for) (?:that|this|it|the above))?"
                + "|(?:please )?(?:explain|clarify|elaborate on) (?:that|this|it|the above)(?: (?:again|in more detail|further))?"
                + "|why(?: is that| does that happen)?|(?:please )?(?:continue|go on)")
                || q.replace(" ", "").matches("(?:请)?(?:(?:再|继续|进一步)?(?:举|给|给我|提供)(?:一个|一些|更多|个)?(?:例子|示例)|(?:再|继续|进一步)?(?:解释|说明|展开|详细说明|详细解释)(?:一下|一点|一些)?(?:吗|呢)?|为什么(?:会)?这样|这是为什么|为什么|继续|(?:这个|那个|它|上面(?:的内容)?)(?:是什么意思|为什么|怎么理解|能举例吗))");
    }
    private static DeepSeekException limit(String zh, String en, ReplyLanguage language) {
        return new DeepSeekException(DeepSeekException.Kind.INPUT_LIMIT, language.text(zh, en), 0, null);
    }
}
