package edu.t07.coursemate.rag;

import com.google.gson.JsonParser;
import edu.t07.coursemate.ReplyLanguage;
import edu.t07.coursemate.ai.DeepSeekException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class RagAnswerServiceTest {
    @TempDir Path temporary;

    @Test
    void defaultCodeQuestionRetrievesMapApiRatherThanRawCommentsOrVariableNames() throws Exception {
        TestPdfs.write(temporary.resolve("Lec-3.2-Test.pdf"),
                "Map stores key-value pairs. HashMap counts.merge uses generic types.",
                "Inheritance derives subclasses from superclasses.");
        var kb = new CourseKnowledgeBase(temporary, temporary.resolve("index.json"), new PdfCourseReader());
        new RagAnswerService(kb, (question, sources) -> {
            assertFalse(sources.isEmpty());
            assertEquals(1, sources.getFirst().page());
            assertTrue(sources.stream().noneMatch(c -> c.page() == 2));
            return "{\"course_answer\":\"Map counts values.\",\"general_answer\":\"\",\"source_ids\":[\"S1\"]}";
        }).answer("Explain this code.", "// inheritance\nMap<String, Integer> counts = new HashMap<>(); counts.merge(\"inheritance\", 1, Integer::sum);");
    }

    @Test
    void unrelatedTaskCannotGainCourseEvidenceFromSelectedJavaCode() throws Exception {
        TestPdfs.write(temporary.resolve("Lec-3.2-Test.pdf"), "Map HashMap generics merge key value.");
        var kb = new CourseKnowledgeBase(temporary, temporary.resolve("index.json"), new PdfCourseReader());
        new RagAnswerService(kb, (question, sources) -> {
            assertTrue(sources.isEmpty()); return "General physics.";
        }).answer("Explain quantum entanglement and photon polarization.", "Map<String, Integer> counts = new HashMap<>();");
    }

    @Test
    void ordinaryPdfFilenamesAreDataInBothGeneralAndGroundedAnswers() throws Exception {
        var sources = List.of(new CourseChunk("Lec-1-Test.pdf", 2, "Use files."));
        String json = "{\"course_answer\":\"Use new File(\\\"notes.pdf\\\").\",\"general_answer\":\"Open notes.pdf locally.\",\"source_ids\":[\"S1\"]}";
        assertTrue(RagAnswerService.renderGrounded(json, sources).contains("new File(\"notes.pdf\")"));
        var kb = new CourseKnowledgeBase(temporary, temporary.resolve("index.json"), new PdfCourseReader());
        assertTrue(new RagAnswerService(kb, (q, s) -> "Use new File(\"notes.pdf\").").answer("local files").contains("notes.pdf"));
    }

    @Test
    void eachClaimKeepsItsOwnSuppliedSourceAndOriginalMultilineExcerpt() throws Exception {
        var sources = List.of(new CourseChunk("Lec-3.2-Test.pdf", 15, "Map example:\n  counts.merge(\"java\", 1, Integer::sum);"),
                new CourseChunk("Lec-3.2-Test.pdf", 4, "Generics enforce type safety."));
        String json = "{\"course_answer\":[{\"text\":\"Count using merge.\",\"source_ids\":[\"S1\"]},{\"text\":\"Type checks.\",\"source_ids\":[\"S2\"]}],\"general_answer\":\"Extra advice.\",\"source_ids\":[\"S1\",\"S2\"]}";
        String result = RagAnswerService.renderGrounded(json, sources);
        assertTrue(result.contains("Count using merge.\n[本段来源：S1]"));
        assertTrue(result.contains("Type checks.\n[本段来源：S2]"));
        assertTrue(result.contains(sources.getFirst().text()));
        assertTrue(result.contains("结论未经自动事实验证"));
        assertTrue(result.contains("不由课件证明"));
    }

    @Test
    void rejectsUnprovidedClaimIdsAndMismatchedUnion() {
        var sources = List.of(new CourseChunk("Lec-1-Test.pdf", 2, "Actual material."));
        for (String json : List.of(
                "{\"course_answer\":[{\"text\":\"A\",\"source_ids\":[\"S2\"]}],\"general_answer\":\"\",\"source_ids\":[\"S1\"]}",
                "{\"course_answer\":[{\"text\":\"A\",\"source_ids\":[\"S1\"]}],\"general_answer\":\"\",\"source_ids\":[]}")) {
            assertThrows(DeepSeekException.class, () -> RagAnswerService.renderGrounded(json, sources));
        }
    }

    @Test
    void plainStringObjectClaimAndExtraFieldsStillRenderTheCheckedSource() throws Exception {
        var sources = List.of(new CourseChunk("Lec-3.2-Test.pdf", 15, "Map counts values."));
        for (String json : List.of(
                "{\"course_answer\":\"Map counts values.\",\"general_answer\":\"\",\"source_ids\":[\"S1\"],\"code\":\"counts.merge()\"}",
                "{\"course_answer\":{\"text\":\"Map counts values.\",\"source_ids\":[\"S1\"],\"code\":\"counts.merge()\"},\"general_answer\":\"\",\"source_ids\":[1]}",
                "{\"course_answer\":[\"Map counts values.\"],\"general_answer\":null,\"source_ids\":[\"s1\"]}")) {
            String result = RagAnswerService.renderGrounded(json, sources);
            assertTrue(result.contains("Map counts values."));
            assertTrue(result.contains("Lec-3.2-Test.pdf"));
            assertFalse(result.contains("counts.merge()"));
        }
    }

    @Test
    void codeFencesContainingSourceLikeStringsAreNotAttributions() throws Exception {
        String json = "{\"course_answer\":\"```java\\nString label = \\\"[S9]\\\";\\n```\",\"general_answer\":\"\",\"source_ids\":[\"S1\"]}";
        assertTrue(RagAnswerService.renderGrounded(json, List.of(new CourseChunk("Lec-1-Test.pdf", 2, "String literals."))).contains("String label"));
    }

    @Test
    void selectedCodeAndEditableTaskReachTheSameGeneratorAndKeepRealCourseSources() throws Exception {
        TestPdfs.write(temporary.resolve("Lec-2-Test.pdf"), "Inheritance derives subclasses from superclasses.");
        var kb = new CourseKnowledgeBase(temporary, temporary.resolve("index.json"), new PdfCourseReader());
        String code = "class Child extends Parent { String label = \"中文\"; }\n";
        String answer = new RagAnswerService(kb, (modelQuestion, sources) -> {
            var context = JsonParser.parseString(modelQuestion).getAsJsonObject();
            assertEquals("Explain how inheritance is used here.", context.get("question").getAsString());
            assertEquals(code, context.get("selected_code").getAsString());
            assertFalse(sources.isEmpty());
            assertEquals("Lec-2-Test.pdf", sources.getFirst().fileName());
            return "{\"course_answer\":\"Child 继承 Parent。\",\"general_answer\":\"\",\"source_ids\":[\"S1\"]}";
        }).answer("Explain how inheritance is used here.", code);
        assertTrue(answer.contains("Lec-2-Test.pdf — PDF 第 1 页"));
    }

    @Test
    void sourceCodeIsContextNotAnUnrelatedRetrievalQueryAndNormalChatRemainsPlainText() throws Exception {
        TestPdfs.write(temporary.resolve("Lec-2-Test.pdf"), "Inheritance derives subclasses from superclasses.");
        var kb = new CourseKnowledgeBase(temporary, temporary.resolve("index.json"), new PdfCourseReader());
        String code = "// inheritance\nclass Example {}";
        String result = new RagAnswerService(kb, (modelQuestion, sources) -> {
            assertTrue(sources.isEmpty(), "Incidental source comments must not become course evidence");
            assertEquals(code, JsonParser.parseString(modelQuestion).getAsJsonObject().get("selected_code").getAsString());
            return "This is an empty class.";
        }).answer("Explain this code.", code);
        assertTrue(result.contains(RagAnswerService.GENERAL_LABEL));
        assertFalse(result.contains("Lec-2-Test.pdf"));
        var ordinary = new RagAnswerService(kb, (question, sources) -> {
            assertEquals("astronomyzz", question);
            return "General answer.";
        });
        assertTrue(ordinary.answer("astronomyzz").contains("General answer."));
    }

    @Test
    void codeQuestionFailureRemainsAnApiErrorAndBlankTaskCannotSend() {
        var kb = new CourseKnowledgeBase(temporary, temporary.resolve("index.json"), new PdfCourseReader());
        AtomicInteger calls = new AtomicInteger();
        DeepSeekException failure = new DeepSeekException("模拟网络连接失败");
        var service = new RagAnswerService(kb, (question, sources) -> { calls.incrementAndGet(); throw failure; });
        assertThrows(DeepSeekException.class, () -> service.answer(" ", "int x = 1;"));
        assertEquals(0, calls.get());
        assertSame(failure, assertThrows(DeepSeekException.class, () -> service.answer("Explain this code.", "int x = 1;")));
        assertEquals(1, calls.get());
    }

    @Test
    void noMatchClearlyLabelsGeneralKnowledgeAndSendsNoCourseContext() throws Exception {
        TestPdfs.write(temporary.resolve("Lec-2-Test.pdf"), "Inheritance extends superclass.");
        var kb = new CourseKnowledgeBase(temporary, temporary.resolve("index.json"), new PdfCourseReader());
        var service = new RagAnswerService(kb, (question, sources) -> {
            assertTrue(sources.isEmpty());
            assertEquals("请解释量子纠缠实验中的光子偏振测量。", question);
            return "量子纠缠描述联合量子态；偏振测量的统计相关性由该联合态决定。";
        });
        String result = service.answer("请解释量子纠缠实验中的光子偏振测量。");
        assertTrue(result.contains(RagAnswerService.ZH_NOTICE));
        assertTrue(result.contains(RagAnswerService.GENERAL_LABEL));
        assertTrue(result.contains("量子纠缠描述联合量子态"));
        assertFalse(result.contains("[错误]"));
        assertFalse(result.contains("Lec-2-Test.pdf"));
    }

    @Test
    void weakCandidatesAreNotSentAsCourseEvidence() throws Exception {
        TestPdfs.write(temporary.resolve("Lec-1-Test.pdf"), "Simple syntax measurements and program values.");
        var kb = new CourseKnowledgeBase(temporary, temporary.resolve("index.json"), new PdfCourseReader());
        String result = new RagAnswerService(kb, (question, sources) -> {
            assertTrue(sources.isEmpty(), "Low-relevance candidates must be removed before the model call");
            return "A general explanation of orbital geology.";
        }).answer("Explain orbital geology measurements in simple terms.");
        assertTrue(result.contains(RagAnswerService.GENERAL_LABEL));
        assertFalse(result.contains("课程资料回答"));
        assertFalse(result.contains("Lec-1-Test.pdf"));
    }

    @Test
    void successfulGeneralAnswerAfterCandidateReviewHasNoFailureLabelOrCourseSources() throws Exception {
        String answer = RagAnswerService.renderGrounded("{\"course_answer\":\"\","
                + "\"general_answer\":\"A valid general explanation.\",\"source_ids\":[]}",
                List.of(new CourseChunk("Lec-1-Test.pdf", 14, "Some retrieved candidate.")));
        assertTrue(answer.contains("候选课件未被回答采用"));
        assertFalse(answer.contains(RagAnswerService.ZH_NOTICE));
        assertTrue(answer.contains("A valid general explanation."));
        assertFalse(answer.contains("模型未能"));
        assertFalse(answer.contains("课程资料回答"));
        assertFalse(answer.contains("课程来源："));
        assertFalse(answer.contains("Lec-1-Test.pdf"));
        assertFalse(answer.contains("本次实际检索到"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Java inheritance", "Explain orbital geology measurements in simple terms."})
    void apiFailureIsPropagatedInsteadOfConvertedToNoMatch(String question) throws Exception {
        TestPdfs.write(temporary.resolve("Lec-2-Test.pdf"), "Inheritance derives subclasses from superclasses.");
        var kb = new CourseKnowledgeBase(temporary, temporary.resolve("index.json"), new PdfCourseReader());
        DeepSeekException failure = new DeepSeekException("模拟认证失败（HTTP 401）");
        AtomicInteger calls = new AtomicInteger();
        var service = new RagAnswerService(kb, (input, sources) -> {
            calls.incrementAndGet();
            assertEquals(input.contains("inheritance"), !sources.isEmpty());
            throw failure;
        });
        DeepSeekException actual = assertThrows(DeepSeekException.class, () -> service.answer(question));
        assertSame(failure, actual);
        assertFalse(actual.getMessage().contains(RagAnswerService.GENERAL_NOTICE));
        assertEquals(1, calls.get(), "API errors must not trigger a disguised general fallback call");
    }

    @Test
    void matchedQuestionStillUsesRetrievedContextAndProgramGeneratedCitation() throws Exception {
        TestPdfs.write(temporary.resolve("Lec-2-Test.pdf"), "Inheritance extends superclass.");
        var kb = new CourseKnowledgeBase(temporary, temporary.resolve("index.json"), new PdfCourseReader());
        String result = new RagAnswerService(kb, (question, sources) -> {
            assertFalse(sources.isEmpty());
            assertEquals("Lec-2-Test.pdf", sources.getFirst().fileName());
            return "{\"course_answer\":\"继承允许派生子类。\",\"general_answer\":\"\",\"source_ids\":[\"S1\"]}";
        }).answer("Java inheritance");
        assertTrue(result.contains("[课程资料回答]"));
        assertTrue(result.contains("Lec-2-Test.pdf — PDF 第 1 页"));
        assertFalse(result.contains(RagAnswerService.GENERAL_NOTICE));
    }

    @Test
    void programRendersOnlyVerifiedSourcesAndSeparatesGeneralKnowledge() throws Exception {
        var sources = List.of(new CourseChunk("Lec-2-RealName.pdf", 19, "Inheritance extends classes."),
                new CourseChunk("Lec-2-RealName.pdf", 20, "Overriding methods."));
        String rendered = RagAnswerService.renderGrounded("{\"course_answer\":\"继承可复用父类行为。\","
                + "\"general_answer\":\"补充示例。\",\"source_ids\":[\"S1\",\"S2\"]}", sources);
        assertTrue(rendered.contains("课程资料回答"));
        assertTrue(rendered.contains("通用知识补充"));
        assertTrue(rendered.contains("Lec-2-RealName.pdf — PDF 第 19 页"));
        assertTrue(rendered.contains("Lec-2-RealName.pdf — PDF 第 20 页"));
    }

    @Test
    void rejectsInventedIdsFilenamesPagesAndMalformedGroundedAnswers() {
        var sources = List.of(new CourseChunk("Lec-2-RealName.pdf", 19, "Inheritance."));
        for (String response : List.of(
                "{\"course_answer\":\"answer\",\"general_answer\":\"\",\"source_ids\":[\"S4\"]}",
                "{\"course_answer\":\"Invented.pdf page 999\",\"general_answer\":\"\",\"source_ids\":[\"S1\"]}",
                "{\"course_answer\":\"answer\",\"general_answer\":\"\",\"source_ids\":[]}", "not json")) {
            DeepSeekException error = assertThrows(DeepSeekException.class,
                    () -> RagAnswerService.renderGrounded(response, sources));
            assertFalse(error.getMessage().contains("Invented.pdf"));
            assertNull(error.getCause());
        }
    }

    @Test
    void deepSeekFailureIsSafeAndSubsequentQuestionStillWorks() throws Exception {
        TestPdfs.write(temporary.resolve("Lec-2-Test.pdf"), "Inheritance extends superclass.");
        var kb = new CourseKnowledgeBase(temporary, temporary.resolve("index.json"), new PdfCourseReader());
        AtomicInteger calls = new AtomicInteger();
        var service = new RagAnswerService(kb, (question, sources) -> {
            if (calls.incrementAndGet() == 1) throw new DeepSeekException("模拟认证失败（HTTP 401）");
            return "Recovered general answer.";
        });
        assertTrue(assertThrows(DeepSeekException.class, () -> service.answer("astronomyzz")).getMessage().contains("401"));
        assertTrue(service.answer("astronomyzz").contains("Recovered general answer."));
    }

    @Test
    void unavailableKnowledgeBaseIsNotReportedAsSuccessfulRetrieval() throws Exception {
        var kb = new CourseKnowledgeBase(temporary.resolve("missing"), temporary.resolve("index.json"), new PdfCourseReader());
        String result = new RagAnswerService(kb, (question, sources) -> "General answer.").answer("generics");
        assertTrue(result.contains(RagAnswerService.ZH_NOTICE));
        assertTrue(result.contains("无法读取课程目录"));
    }

    @Test
    void followUpUsesPriorQuestionWhenTheNewWordingHasNoCourseTerms() throws Exception {
        TestPdfs.write(temporary.resolve("Lec-2-Test.pdf"), "Inheritance derives subclasses from superclasses.");
        var kb = new CourseKnowledgeBase(temporary, temporary.resolve("index.json"), new PdfCourseReader());
        String result = new RagAnswerService(kb, (modelQuestion, sources) -> {
            assertFalse(sources.isEmpty());
            var context = JsonParser.parseString(modelQuestion).getAsJsonObject();
            assertEquals("再举一个例子", context.get("question").getAsString());
            assertEquals("请根据课件解释继承", context.get("prior_question").getAsString());
            assertEquals("子类可以复用父类的字段和方法。", context.get("prior_answer").getAsString());
            assertFalse(modelQuestion.contains(".pdf"));
            return "{\"course_answer\":\"例如 Child 继承 Parent。\",\"general_answer\":\"\",\"source_ids\":[\"S1\"]}";
        }).answer("再举一个例子", "", "请根据课件解释继承",
                "子类可以复用父类的字段和方法。\n[S1] Lec-2-Test.pdf — PDF 第 1 页");
        assertTrue(result.contains("Lec-2-Test.pdf — PDF 第 1 页"));
    }

    @Test
    void englishReplyUsesEnglishFramesAndMarksTheModelRequest() throws Exception {
        TestPdfs.write(temporary.resolve("Lec-2-Test.pdf"), "Inheritance derives subclasses from superclasses.");
        var kb = new CourseKnowledgeBase(temporary, temporary.resolve("index.json"), new PdfCourseReader());
        String result = new RagAnswerService(kb, (modelQuestion, sources) -> {
            var context = JsonParser.parseString(modelQuestion).getAsJsonObject();
            assertEquals("en", context.get("answer_language").getAsString());
            assertEquals("Explain inheritance.", context.get("question").getAsString());
            return "{\"course_answer\":\"A subclass reuses its superclass.\",\"general_answer\":\"\",\"source_ids\":[\"S1\"]}";
        }).answer("Explain inheritance.", "", "", "", ReplyLanguage.EN);
        assertTrue(result.contains("[From the course materials]"));
        assertTrue(result.contains("Lec-2-Test.pdf — PDF page 1"));
        assertFalse(result.contains("PDF 第"));
    }

    @Test
    void changingTopicOrSelectingNewCodeDoesNotBorrowThePreviousCourseEvidence() throws Exception {
        TestPdfs.write(temporary.resolve("Lec-3.2-Test.pdf"), "Generics type safety parameterized types.");
        var kb = new CourseKnowledgeBase(temporary, temporary.resolve("index.json"), new PdfCourseReader());
        var service = new RagAnswerService(kb, (request, sources) -> {
            assertTrue(sources.isEmpty(), "An unrelated question must not inherit the prior course sources");
            assertEquals("Java generics", JsonParser.parseString(request).getAsJsonObject().get("prior_question").getAsString());
            return "General answer.";
        });
        for (String question : List.of("今晚吃什么？", "Please give me an example dinner recipe.",
                "Explain quantum entanglement and photon polarization.")) {
            service.answer(question, "", "Java generics", "Earlier answer.");
        }
        service.answer("Explain this code.", "int x = 1;", "Java generics", "Earlier answer.");
    }

    @Test
    void englishFollowUpUsesOnlyThePriorQuestionForRetrievalAndDropsBothSourceAppendices() throws Exception {
        TestPdfs.write(temporary.resolve("Lec-2-Test.pdf"), "Inheritance derives subclasses from superclasses.");
        var kb = new CourseKnowledgeBase(temporary, temporary.resolve("index.json"), new PdfCourseReader());
        var sources = List.of(new CourseChunk("Lec-2-Test.pdf", 1, "EXCERPT_SENTINEL inheritance details"));
        for (ReplyLanguage language : ReplyLanguage.values()) {
            String prior = RagAnswerService.renderGrounded("{\"course_answer\":[{\"text\":\"A child reuses its parent.\",\"source_ids\":[\"S1\"]}],\"general_answer\":\"Extra guidance.\",\"source_ids\":[\"S1\"]}", sources, language);
            new RagAnswerService(kb, (request, retrieved) -> {
                assertFalse(retrieved.isEmpty());
                var context = JsonParser.parseString(request).getAsJsonObject();
                String remembered = context.get("prior_answer").getAsString();
                assertTrue(remembered.contains("A child reuses its parent."));
                assertTrue(remembered.contains("Extra guidance."));
                assertFalse(remembered.contains("EXCERPT_SENTINEL"));
                assertFalse(remembered.contains(".pdf"));
                assertFalse(remembered.contains("S1"));
                return "{\"course_answer\":\"Child extends Parent.\",\"general_answer\":\"\",\"source_ids\":[\"S1\"]}";
            }).answer("Please give me another example.", "", "Java inheritance", prior, ReplyLanguage.EN);
        }
    }

    @Test
    void englishCodeRetrievalAndPerParagraphCitationsSurviveTheMerge() throws Exception {
        TestPdfs.write(temporary.resolve("Lec-3.2-Test.pdf"), "Map HashMap merge generics type safety.");
        var kb = new CourseKnowledgeBase(temporary, temporary.resolve("index.json"), new PdfCourseReader());
        String code = "Map<String, Integer> values = new HashMap<>(); values.merge(\"notes.pdf\", 1, Integer::sum);";
        String answer = new RagAnswerService(kb, (request, sources) -> {
            assertFalse(sources.isEmpty());
            var context = JsonParser.parseString(request).getAsJsonObject();
            assertEquals("en", context.get("answer_language").getAsString());
            assertEquals(code, context.get("selected_code").getAsString());
            return "{\"course_answer\":[{\"text\":\"Map merges counts.\",\"source_ids\":[\"S1\"]}],\"general_answer\":\"new File(\\\"notes.pdf\\\") is ordinary code.\",\"source_ids\":[\"S1\"]}";
        }).answer("Explain this code.", code, "", "", ReplyLanguage.EN);
        assertTrue(answer.contains("[Sources for this paragraph: S1]"));
        assertTrue(answer.contains("Lec-3.2-Test.pdf — PDF page 1"));
        assertTrue(answer.contains("claims are not automatically fact-checked"));
        assertTrue(answer.contains("notes.pdf"));
        assertFalse(answer.contains("PDF 第"));
    }

    @Test
    void noMatchCannotDisplayModelInventedCourseCitation() {
        var kb = new CourseKnowledgeBase(temporary, temporary.resolve("index.json"), new PdfCourseReader());
        var service = new RagAnswerService(kb, (question, sources) -> "Invented.pdf page 999 [S1]");
        DeepSeekException error = assertThrows(DeepSeekException.class, () -> service.answer("astronomyzz"));
        assertTrue(error.getMessage().contains("未检索到课程资料"));
        assertFalse(error.getMessage().contains("Invented.pdf"));
    }
}
