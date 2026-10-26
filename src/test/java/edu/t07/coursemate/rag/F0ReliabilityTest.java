package edu.t07.coursemate.rag;

import edu.t07.coursemate.ReplyLanguage;
import edu.t07.coursemate.ai.*;
import edu.t07.coursemate.chat.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class F0ReliabilityTest {
    @TempDir Path temp;
    @Test void compoundFollowUpContainsThePreviousTopicAndHistory() throws Exception {
        var session=new ChatSession();var first=session.prepare("为什么用父类类型的变量，可以调用到子类重写的方法？",null,true,ReplyLanguage.ZH);session.commit(first,"运行时多态。原回答快照。");
        var next=session.prepare("再举一个例子，并说明实际调用哪个方法。",null,true,ReplyLanguage.ZH);
        assertEquals(first.resolvedQuestion(),next.resolvedQuestion());assertEquals(ChatSession.Relation.CONTINUATION,next.relation());assertEquals(ChatSession.Task.EXAMPLE_EXTENSION,next.task());assertEquals("运行时多态。原回答快照。",next.history().getFirst().answer());
        var changed=session.prepare("再举一个例子，并解释今晚吃什么。",null,true,ReplyLanguage.ZH);assertEquals(changed.question(),changed.resolvedQuestion());assertTrue(changed.history().isEmpty());
    }
    @Test void fencedJsonAndAbsentSupplementPreserveTheCitationWhitelist() throws Exception {
        TestPdfs.write(temp.resolve("Lec-2-Test.pdf"),"Inheritance extends superclass.");var kb=new CourseKnowledgeBase(temp,temp.resolve("i.json"),new PdfCourseReader());
        String valid="```json\n{\"course_answer\":[{\"text\":\"A subclass inherits.\",\"source_ids\":[\"S1\"]}],\"general_answer\":null,\"source_ids\":[\"S1\"]}\n```";
        var request=new ChatSession().prepare("inheritance",null,true,ReplyLanguage.EN);
        var result=RagAnswerService.structured(kb,(q,s)->ModelResponse.complete(valid)).ask(request);assertEquals(1,result.sources().size());assertEquals("A subclass inherits.",result.prose());
        assertThrows(DeepSeekException.class,()->RagAnswerService.structured(kb,(q,s)->ModelResponse.complete(valid.replace("S1","S9"))).ask(request));
    }
    @Test void fiveFollowUpsRetainResolvedTopicAndEveryTurnRetrievesAgain() throws Exception {
        TestPdfs.write(temp.resolve("Lec-2-Test.pdf"), "Inheritance derives subclasses from superclasses.");
        var kb=new CourseKnowledgeBase(temp,temp.resolve("index.json"),new PdfCourseReader());
        AtomicInteger calls=new AtomicInteger();
        var service=RagAnswerService.structured(kb,(q,s)->{calls.incrementAndGet();assertFalse(s.isEmpty());return ModelResponse.complete("{\"course_answer\":[{\"text\":\"Child extends Parent.\",\"source_ids\":[\"S1\"]}],\"general_answer\":\"\",\"source_ids\":[\"S1\"]}");});
        var session=new ChatSession();
        for(String q:List.of("Explain inheritance.","Give another example.","再举一个例子。","Please explain that further.","继续","Why?")){
            var request=session.prepare(q,null,true,ReplyLanguage.EN);assertEquals("Explain inheritance.",request.resolvedQuestion());
            var answer=service.ask(request);assertEquals(request.id(),answer.turnId());assertTrue(session.commit(request,answer.prose()));
        }
        assertEquals(6,calls.get());
    }
    @Test void newTopicNewSelectionCancellationAndClearHaveExplicitBoundaries() throws Exception {
        var s=new ChatSession();var code=ChatSession.Code.of("Map<String,Integer> m=new HashMap<>();");
        var first=s.prepare("Explain this code.",code,true,ReplyLanguage.EN);s.commit(first,"A map.");
        var follow=s.prepare("Give another example.",null,true,ReplyLanguage.EN);assertTrue(follow.historicalCode());assertEquals(code,follow.code());
        s.cancel();assertFalse(s.commit(follow,"Late result"));
        var fresh=s.prepare("Explain this code.",ChatSession.Code.of("int i=0;"),true,ReplyLanguage.EN);assertFalse(fresh.historicalCode());
        s.commit(fresh,"A number.");var dinner=s.prepare("What should I eat for dinner?",null,true,ReplyLanguage.EN);assertTrue(dinner.code().empty());assertEquals(dinner.question(),dinner.resolvedQuestion());
        s.clear();assertFalse(s.current(dinner));assertEquals("",s.topic());assertTrue(s.rememberedCode().empty());
    }
    @Test void excessiveInputIsRejectedWithoutSilentCodeTruncation() {
        var s=new ChatSession();var e=assertThrows(DeepSeekException.class,()->s.prepare("Explain this code.",ChatSession.Code.of("x".repeat(16001)),true,ReplyLanguage.EN));
        assertEquals(DeepSeekException.Kind.INPUT_LIMIT,e.kind());
    }
    @Test void politeCodeTasksPreserveFeaturesAndIgnoreLiteralsAndUnrelatedSubjects() {
        String code="Map<String,Integer> m=new HashMap<>();m.merge(\"inheritance\",1,Integer::sum);";
        for(String q:List.of("Explain this code.","Could you explain this code?","Can you walk me through this snippet?","检查这段代码。"))assertTrue(CodeRetrievalQuery.build(q,code).contains("map merge generics"));
        assertEquals("Explain this code.",CodeRetrievalQuery.build("Explain this code.","// Map\nString x=\"HashMap\";"));
        assertEquals("Explain photon polarization.",CodeRetrievalQuery.build("Explain photon polarization.",code));
    }
    @Test void unknownSelectedMethodCannotAcquireAnIncidentalCourseCitation() throws Exception {
        TestPdfs.write(temp.resolve("Lec-3.1-Test.pdf"), "A chosen catch clause handles exceptions.");
        var kb=new CourseKnowledgeBase(temp,temp.resolve("index.json"),new PdfCourseReader());
        var service=RagAnswerService.structured(kb,(q,s)->{assertTrue(s.isEmpty());return ModelResponse.complete("The method implementation is not supplied.");});
        var request=new ChatSession().prepare("Explain the chosen code.",ChatSession.Code.of("System.out.println(countWords(\"collections\"));"),true,ReplyLanguage.EN);
        assertEquals("",request.retrievalQuery());assertTrue(service.ask(request).sources().isEmpty());
    }
    @Test void incompleteJsonWithLengthIsTruncationAndNeverCitationFailureOrRetry() throws Exception {
        TestPdfs.write(temp.resolve("Lec-2-Test.pdf"),"Inheritance extends superclass.");
        var kb=new CourseKnowledgeBase(temp,temp.resolve("i.json"),new PdfCourseReader());AtomicInteger calls=new AtomicInteger();
        var service=RagAnswerService.structured(kb,(q,s)->{calls.incrementAndGet();return new ModelResponse("{\"course_answer\":", "length",null,"");});
        var result=service.ask(new ChatSession().prepare("Explain inheritance.",null,true,ReplyLanguage.EN));
        assertEquals(AnswerResult.Completion.TRUNCATED,result.completion());assertTrue(result.sources().isEmpty());assertTrue(result.claims().isEmpty());assertEquals(1,calls.get());
    }
    @Test void retrievedButUnusedDoesNotBecomeNoHits() throws Exception {
        TestPdfs.write(temp.resolve("Lec-2-Test.pdf"),"Inheritance extends superclass.");var kb=new CourseKnowledgeBase(temp,temp.resolve("i.json"),new PdfCourseReader());
        var service=RagAnswerService.structured(kb,(q,s)->ModelResponse.complete("{\"course_answer\":[],\"general_answer\":\"Open notes.pdf.\",\"source_ids\":[]}"));
        var result=service.ask(new ChatSession().prepare("Explain inheritance.",null,true,ReplyLanguage.EN));
        assertEquals(AnswerResult.Basis.CANDIDATES_UNUSED,result.basis());assertFalse(result.retrieval().evidence().isEmpty());assertTrue(result.sources().isEmpty());
    }
    @Test void metadataIsStableVersionedAndComplementaryRangesSurvive() {
        var raw=PdfCourseReader.split("Lec-2-Test.pdf",2,"Generics enable type safety. ".repeat(90));var chunks=CourseChunk.bindDocument(raw,"hash-A");
        assertEquals(chunks,CourseChunk.bindDocument(raw,"hash-A"));assertNotEquals(chunks.getFirst().chunkId(),CourseChunk.bindDocument(raw,"hash-B").getFirst().chunkId());
        assertEquals(chunks.get(1).chunkId(),chunks.getFirst().nextId());assertEquals(chunks.getFirst().chunkId(),chunks.get(1).previousId());
        var index=new KeywordIndex(List.of(new CourseChunk("Lec-3.2-Test.pdf",5,"Generics enable type safety."),new CourseChunk("Lec-3.2-Test.pdf",5,"Generics allow parameterized containers."),new CourseChunk("Lec-1-Test.pdf",4,"Arrays store values.")));
        assertEquals(2,index.search("generics").size());assertTrue(chunks.stream().allMatch(c->c.endOffset()-c.startOffset()==c.text().length()));
    }
    @Test void parentReferenceDispatchTargetsPolymorphismRatherThanItsBroadPrerequisite() {
        var index=new KeywordIndex(List.of(new CourseChunk("Lec-2-Test.pdf",19,"Inheritance subclasses superclass fields methods."),new CourseChunk("Lec-2-Test.pdf",22,"Polymorphism uses virtual method invocation and overriding.")));
        assertEquals(22,index.search("为什么用父类引用可以调用子类的方法？").getFirst().page());
    }
    @Test void evidenceAdmissionSeparatesWeakCandidatesAndPrefersJavaOverCpp() {
        var index=new KeywordIndex(List.of(new CourseChunk("Lec-3.2-Test.pdf",2,"C++ templates generics generics generics generics."),new CourseChunk("Lec-3.2-Test.pdf",5,"Java generics parameterized types enforce safety. Map stores entries.")));
        var result=index.retrieve("Explain generics.");assertEquals(5,result.evidence().getFirst().page());assertTrue(result.evidence().stream().noneMatch(c->c.text().contains("C++")));
        var outside=index.retrieve("Explain orbital geology with a Map.");assertEquals(RetrievalResult.Status.INSUFFICIENT,outside.status());assertFalse(outside.candidates().isEmpty());assertTrue(outside.evidence().isEmpty());
    }
}
