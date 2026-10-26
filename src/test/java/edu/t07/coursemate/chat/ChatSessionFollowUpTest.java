package edu.t07.coursemate.chat;

import edu.t07.coursemate.ReplyLanguage;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ChatSessionFollowUpTest {
    private static final ChatSession.Code CODE = ChatSession.Code.of("Map<String, Integer> counts = new HashMap");
    private static ChatSession conversation() throws Exception {
        var session = new ChatSession();
        var first = session.prepare("解释这段代码。", CODE, true, ReplyLanguage.ZH);
        assertTrue(session.commit(first, "这里只展示了部分声明；如果这是完整语句，需要补上构造调用。"));
        return session;
    }

    @Test void repairAndResultQuestionsRetainExactSelectionAndBoundedHistory() throws Exception {
        var session = conversation();
        for (String question : List.of("修改后应该是什么样？", "能给我完整的修改版本吗？", "那这个怎么改？", "帮我改一下。", "那 merge 应该怎么写？",
                "What would the fixed code look like?", "Can you show the corrected version?",
                "Could you fix it?", "Would it still work after that change?")) {
            assertTrue(session.willUseHistoricalCode(question), question);
            var next = session.prepare(question, null, true, ReplyLanguage.ZH);
            assertEquals(ChatSession.Relation.CONTINUATION, next.relation(), question);
            assertEquals("解释这段代码。", next.resolvedQuestion());
            assertEquals(CODE, next.code());
            assertTrue(next.historicalCode());
            assertFalse(next.history().isEmpty());
            assertTrue(next.history().size() <= ChatSession.MAX_HISTORY);
            assertTrue(session.commit(next, "A proposed corrected snippet, not a change to the selected snapshot."));
        }
    }

    @Test void relatedConceptClarificationRetainsCodeButNewSubjectsDoNot() throws Exception {
        var session = conversation();
        var related = session.prepare("那这里的泛型应该怎么改？", null, true, ReplyLanguage.ZH);
        assertTrue(related.historicalCode());
        assertTrue(related.retrievalQuery().contains("泛型"));
        for (String question : List.of("请介绍异常处理。", "那异常处理应该怎么写？", "What should I eat for dinner?",
                "Explain inheritance.", "请介绍 HashMap 的基本用法。")) {
            var next = session.prepare(question, null, true, ReplyLanguage.ZH);
            assertEquals(question, next.resolvedQuestion());
            assertTrue(next.code().empty(), question);
            assertTrue(next.history().isEmpty(), question);
        }
    }

    @Test void disablingReuseDoesNotEraseConversationalReferenceAndNewSelectionWins() throws Exception {
        var session = conversation();
        var disabled = session.prepare("修改后应该是什么样？", null, false, ReplyLanguage.ZH);
        assertFalse(disabled.historicalCode());
        assertTrue(disabled.code().empty());
        assertFalse(disabled.history().isEmpty());
        var fresh = session.prepare("修改后应该是什么样？", ChatSession.Code.of("int count = 1;"), true, ReplyLanguage.ZH);
        assertFalse(fresh.historicalCode());
        assertEquals("int count = 1;", fresh.code().text());
        assertTrue(fresh.history().isEmpty());
        session.clear();
        assertFalse(session.willUseHistoricalCode("修改后应该是什么样？"));
    }
    @Test void aNewSubjectReplacesTheHistoryBeforeItsFirstFollowUp() throws Exception {
        var session=conversation();
        var changed=session.prepare("Explain checked exceptions.",null,true,ReplyLanguage.EN);
        session.commit(changed,"EXCEPTION_TOPIC_ONLY");
        var follow=session.prepare("Give another example.",null,true,ReplyLanguage.EN);
        assertEquals(1,follow.history().size());
        assertEquals("EXCEPTION_TOPIC_ONLY",follow.history().getFirst().answer());
        assertTrue(follow.code().empty());
    }
    @Test void taskDescribesTheRequestedOperationIndependentlyOfContinuation() throws Exception {
        var session=conversation();
        assertEquals("EXAMPLE_EXTENSION",session.prepare("Give another example.",null,true,ReplyLanguage.EN).task().name());
        assertEquals("CODE_REVISION",session.prepare("Could you fix it?",null,true,ReplyLanguage.EN).task().name());
        assertEquals("DEBUGGING",session.prepare("Why does this code throw an error?",null,true,ReplyLanguage.EN).task().name());
        assertEquals(ChatSession.Task.CODE_EXPLANATION,session.prepare("Explain that example in more detail.",null,true,ReplyLanguage.EN).task());
    }
    @Test void correctingAnInterpretationReusesTheSelectionWithoutRepeatingTheOldAnswer() throws Exception {
        var session=conversation();
        var corrected=session.prepare("我不是在问构造语法，我指的是 merge 的统计过程。",null,true,ReplyLanguage.ZH);
        assertEquals(CODE,corrected.code());
        assertTrue(corrected.historicalCode());
        assertTrue(corrected.history().isEmpty());
        assertFalse(corrected.retrievalQuery().contains("构造语法"));
        session.commit(corrected,"CORRECTED_EXPLANATION");
        var follow=session.prepare("继续解释。",null,true,ReplyLanguage.ZH);
        assertEquals(1,follow.history().size());
        assertEquals("CORRECTED_EXPLANATION",follow.history().getFirst().answer());
    }
    @Test void explicitSubjectCorrectionDropsNegatedTopicsAndUnrelatedCode() throws Exception {
        var session=conversation();
        var corrected=session.prepare("我问的是接口，不是泛型。",null,true,ReplyLanguage.ZH);
        assertTrue(corrected.code().empty());
        assertTrue(corrected.history().isEmpty());
        assertTrue(corrected.retrievalQuery().contains("接口"));
        assertFalse(corrected.retrievalQuery().contains("泛型"));
        session.commit(corrected,"INTERFACE_ONLY");
        var follow=session.prepare("再举一个例子。",null,true,ReplyLanguage.ZH);
        assertEquals("接口",follow.resolvedQuestion());
        assertEquals(1,follow.history().size());
    }
    @Test void resetAndNewSelectionHaveIndependentHistoryBoundaries() throws Exception {
        var session=conversation();
        for(String question:List.of("换个话题，再举一个例子。","New question: explain this example.")){
            assertFalse(session.willUseHistoricalCode(question));
            var next=session.prepare(question,null,true,ReplyLanguage.ZH);
            assertEquals(ChatSession.Relation.NEW_TOPIC,next.relation());assertTrue(next.code().empty());assertTrue(next.history().isEmpty());
        }
        var fresh=session.prepare("Explain this code.",ChatSession.Code.of("int index=0;"),true,ReplyLanguage.EN);
        session.commit(fresh,"NEW_SELECTION_ONLY");
        assertEquals(1,session.prepare("Give another example.",null,true,ReplyLanguage.EN).history().size());
        var corrected=session.prepare("I meant generics, not inheritance.",null,true,ReplyLanguage.EN);
        assertFalse(corrected.retrievalQuery().contains("inheritance"));
        assertTrue(corrected.history().isEmpty());
    }
    @Test void contextBudgetOmitsWholeTurnsAndPreservesCodeWithoutSummaryCalls() throws Exception {
        var session=new ChatSession();var code=ChatSession.Code.of("x".repeat(ChatSession.MAX_CODE));
        var first=session.prepare("Explain this code.",code,true,ReplyLanguage.EN);session.commit(first,"Complete paragraph. ".repeat(1000));
        var next=session.prepare("Give another example.",null,true,ReplyLanguage.EN);
        assertEquals(code,next.code());assertTrue(next.history().isEmpty());assertTrue(next.notices().contains("HISTORY_OMITTED"));
    }
}
