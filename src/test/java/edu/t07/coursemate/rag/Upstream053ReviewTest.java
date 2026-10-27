package edu.t07.coursemate.rag;

import edu.t07.coursemate.ReplyLanguage;
import edu.t07.coursemate.ai.*;
import edu.t07.coursemate.chat.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class Upstream053ReviewTest {
    @TempDir Path temp;
    private CourseKnowledgeBase knowledge() throws Exception {
        TestPdfs.write(temp.resolve("Lec-2-Test.pdf"), "Inheritance derives subclasses from superclasses.");
        return new CourseKnowledgeBase(temp, temp.resolve("index.json"), new PdfCourseReader());
    }
    private static final String VALID = "{\"course_answer\":\"- A subclass inherits.\",\"general_answer\":\"\",\"source_ids\":[\"S1\"]}";

    @ParameterizedTest
    @ValueSource(strings = {"{", "{\"course_answer\":\"Claim\",\"general_answer\":\"\",\"source_ids\":[\"S9\"]}"})
    void malformedOrUnprovidedCitationRetriesOnceThenAcceptsCheckedMarkdown(String rejected) throws Exception {
        var calls = new AtomicInteger();
        var service = RagAnswerService.structured(knowledge(), (q,s) -> ModelResponse.complete(
                calls.incrementAndGet() == 1 ? rejected : VALID));
        var answer = service.ask(new ChatSession().prepare("inheritance", null, true, ReplyLanguage.EN));
        assertEquals(2, calls.get());
        assertEquals(AnswerResult.Completion.COMPLETE, answer.completion());
        assertEquals("- A subclass inherits.", answer.prose());
        assertEquals("S1", answer.sources().getFirst().id());
    }

    @Test
    void transportFailuresAreNotRetried() throws Exception {
        var calls = new AtomicInteger();
        var service = RagAnswerService.structured(knowledge(), (q,s) -> {
            calls.incrementAndGet();
            throw new DeepSeekException(DeepSeekException.Kind.HTTP, "Unavailable", 503, null);
        });
        var error = assertThrows(DeepSeekException.class, () -> service.ask(
                new ChatSession().prepare("inheritance", null, true, ReplyLanguage.EN)));
        assertEquals(DeepSeekException.Kind.HTTP, error.kind());
        assertEquals(1, calls.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.9", "-0.1", "4294967297", "1e100", "true", "null", "{}", "[]", "\"\""})
    void malformedSourceNumbersCannotBeRoundedOrDropped(String id) {
        var sources = java.util.List.of(new CourseChunk("Lec-2-Test.pdf", 1, "Inheritance."));
        String top = "{\"course_answer\":\"Claim\",\"general_answer\":\"\",\"source_ids\":[\"S1\"," + id + "]}";
        String claim = "{\"course_answer\":[{\"text\":\"Claim\",\"source_ids\":[\"S1\"," + id
                + "]}],\"general_answer\":\"\",\"source_ids\":[\"S1\"]}";
        assertThrows(DeepSeekException.class, () -> RagAnswerService.renderGrounded(top, sources));
        assertThrows(DeepSeekException.class, () -> RagAnswerService.renderGrounded(claim, sources));
    }

    @ParameterizedTest
    @ValueSource(strings = {"1", "1.0", "1e0", "\"1\"", "\"s1\"", "\"S1\""})
    void exactIntegerAndStringSourceCompatibilityIsPreserved(String id) throws Exception {
        var sources = java.util.List.of(new CourseChunk("Lec-2-Test.pdf", 1, "Inheritance."));
        String json = "{\"course_answer\":\"Claim\",\"general_answer\":\"\",\"source_ids\":[" + id + "]}";
        assertTrue(RagAnswerService.renderGrounded(json, sources).contains("[S1]"));
    }

    @ParameterizedTest
    @ValueSource(strings = {VALID, "{\"course_answer\":"})
    void lengthFinishReasonAlwaysRemainsTruncatedWithoutRetry(String content) throws Exception {
        var calls = new AtomicInteger();
        var response = new ModelResponse(content, "length", null, "truncated-request");
        var service = RagAnswerService.structured(knowledge(), (q,s) -> { calls.incrementAndGet(); return response; });
        var answer = service.ask(new ChatSession().prepare("inheritance", null, true, ReplyLanguage.EN));
        assertEquals(AnswerResult.Completion.TRUNCATED, answer.completion());
        assertTrue(answer.claims().isEmpty());
        assertTrue(answer.sources().isEmpty());
        assertTrue(answer.prose().isEmpty());
        assertSame(response, answer.model());
        assertEquals(1, calls.get());
    }
}
