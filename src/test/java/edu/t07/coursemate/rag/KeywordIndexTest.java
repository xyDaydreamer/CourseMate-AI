package edu.t07.coursemate.rag;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class KeywordIndexTest {
    @Test
    void longEnglishAndChineseQuestionsRetainTheSameGenericConcept() {
        var index = new KeywordIndex(List.of(new CourseChunk("Lec-3.2-Test.pdf", 4,
                "Generics enable stronger type checks and elimination of casting.")));
        for (String question : List.of("Java generics", "Explain how generics improve type safety and eliminate casts.",
                "请详细说明泛型为什么能够提高类型安全并减少强制类型转换。")) {
            assertEquals(4, index.search(question).getFirst().page());
        }
    }

    @Test
    void chineseIterationAndUnknownTopicsDoNotLoseTheirMeaning() {
        var index = new KeywordIndex(List.of(new CourseChunk("Lec-3.2-Test.pdf", 16,
                "Use entrySet to traverse Map keys and values. Use Iterator.remove during iteration.")));
        assertEquals(16, index.search("怎么遍历 Map 并删除元素？").getFirst().page());
        assertTrue(index.search("Map 量子纠缠光子偏振测量实验").isEmpty());
    }
    @Test
    void teachingPagesRemainWhileScheduleAndReadingListAreExcluded() {
        var index = new KeywordIndex(List.of(
                new CourseChunk("Lec-0-Introduction.pdf", 3, "Programs Week Date Program Inheritance Generics"),
                new CourseChunk("Lec-3.2-Generics.pdf", 17, "Further Reading Oracle Java Tutorials Generics"),
                new CourseChunk("Lec-3.2-Generics.pdf", 1,
                        "Dr. Fan Lecture 3.2 Generics and Collections Java Enterprise Application Development"),
                new CourseChunk("Lec-2-OOP.pdf", 19, "Inheritance derives a subclass from its superclass."),
                new CourseChunk("Lec-3.2-Generics.pdf", 4, "Generics enable compile time type checks.")));
        assertEquals(List.of(19), index.search("inheritance").stream().map(CourseChunk::page).toList());
        assertEquals(List.of(4), index.search("generics").stream().map(CourseChunk::page).toList());
    }

    @Test
    void chineseInheritanceDoesNotMatchWildcardExtendsWithoutInheritance() {
        var index = new KeywordIndex(List.of(
                new CourseChunk("Lec-3.2-Generics.pdf", 10, "Upper bounded wildcards use extends Number."),
                new CourseChunk("Lec-2-OOP.pdf", 19, "Inheritance derives a subclass from a superclass.")));
        var matches = index.search("继承");
        assertEquals(1, matches.size());
        assertEquals(19, matches.getFirst().page());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Explain orbital geology measurements in simple terms.",
            "Explain ocean salinity measurements in simple terms.",
            "Explain historical trade measurements in simple terms."})
    void incidentalKnownWordDoesNotCoverUnknownTopic(String question) {
        var index = new KeywordIndex(List.of(
                new CourseChunk("Lec-1-Test.pdf", 14, "Simple syntax and measurements of program values."),
                new CourseChunk("Lec-2-Test.pdf", 19, "Inheritance derives subclasses from superclasses.")));
        assertFalse(index.search("measurements").isEmpty(), "Fixture must contain a genuine lexical candidate");
        assertTrue(index.search(question).isEmpty(), "One shared word must not establish topic relevance");
    }

    @Test
    void formattingWordsAloneAreNotEvidenceAndSingleCourseConceptsStillWork() {
        var index = new KeywordIndex(List.of(
                new CourseChunk("Lec-1-Test.pdf", 14, "Simple terms describe values."),
                new CourseChunk("Lec-2-Test.pdf", 19, "Inheritance derives subclasses from superclasses."),
                new CourseChunk("Lec-3.2-Test.pdf", 4, "Generics enable type checks.")));
        assertTrue(index.search("Explain in simple terms.").isEmpty());
        assertEquals(19, index.search("Explain Java inheritance in simple terms.").getFirst().page());
        assertEquals(19, index.search("Explain how inheritance is used here.").getFirst().page());
        assertEquals(4, index.search("请根据课件解释泛型的作用").getFirst().page());
    }

    @Test
    void acronymAndItsFullNameAreOneConceptRatherThanUnmatchedRequiredWords() {
        var index = new KeywordIndex(List.of(
                new CourseChunk("Lec-5-Test.pdf", 22, "RAG retrieves relevant passages from documents."),
                new CourseChunk("Lec-2-Test.pdf", 19, "Inheritance derives subclasses.")));
        assertEquals(22, index.search("RAG retrieval augmented generation").getFirst().page());
        assertEquals(22, index.search("retrieval augmented generation").getFirst().page());
    }
}
