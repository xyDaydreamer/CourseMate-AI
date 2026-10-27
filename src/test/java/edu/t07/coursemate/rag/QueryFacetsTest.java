package edu.t07.coursemate.rag;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class QueryFacetsTest {
    @Test
    void bilingualFacetsDoNotBecomeCoreTopics() {
        for (String q : List.of("What are the performance and readability disadvantages of encapsulation?",
                "封装在性能和可读性上有哪些代价？")) {
            var plan = QueryPlan.understand(q);
            assertEquals(Set.of("encapsulation"), plan.topics().keySet());
            assertEquals(Set.of("drawbacks"), plan.intents().keySet());
            assertEquals(Set.of("performance", "readability"), plan.attributes().keySet());
        }
    }

    @Test
    void facetsPreferRelevantSubtopicsWithoutAdmittingOtherSubjects() {
        var index = new KeywordIndex(List.of(
                new CourseChunk("Lec-2-Test.pdf", 2, "Encapsulation encapsulation encapsulation: introduction and definitions."),
                new CourseChunk("Lec-2-Test.pdf", 3, "Drawbacks: encapsulation can impose performance overhead and verbose access."),
                new CourseChunk("Lec-2-Test.pdf", 4, "Drawbacks: cooking has performance costs, readability problems and overhead.")));
        for (String q : List.of("What are the performance drawbacks of encapsulation?", "封装有什么性能代价？")) {
            var result = index.retrieve(q);
            assertEquals(3, result.evidence().getFirst().page());
            assertTrue(result.evidence().stream().noneMatch(c -> c.page() == 4));
        }
    }

    @Test
    void attributesAndIntentAloneCannotEstablishCourseEvidence() {
        var index = new KeywordIndex(List.of(new CourseChunk("Lec-2-Test.pdf", 3,
                "Encapsulation drawbacks: performance overhead and readability costs.")));
        for (String q : List.of("烘焙面包在性能和可读性上有什么代价？",
                "Explain the performance costs and readability drawbacks of sourdough fermentation.",
                "What disadvantages does sourdough fermentation have during overnight temperature control?")) {
            assertTrue(index.search(q).isEmpty());
        }
    }

    @Test
    void unknownSubjectsWithRealLexicalSupportStillWorkWithoutCatalogEntries() {
        var index = new KeywordIndex(List.of(new CourseChunk("Lec-1-Test.pdf", 3,
                "Recursion drawbacks include performance overhead from nested calls.")));
        assertFalse(QueryPlan.hasCourseTopic("recursion"));
        assertEquals(3, index.search("What are the performance drawbacks of recursion?").getFirst().page());
    }

    @Test
    void attributesFromAnUnrelatedClauseDoNotRerankTheCourseSubject() {
        var plan = QueryPlan.understand("Explain generics and also discuss the performance drawbacks of sourdough fermentation.");
        assertTrue(plan.partial());
        assertTrue(plan.intents().isEmpty());
        assertTrue(plan.attributes().isEmpty());
    }
}
