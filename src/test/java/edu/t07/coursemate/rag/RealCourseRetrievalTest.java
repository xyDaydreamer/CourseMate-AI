package edu.t07.coursemate.rag;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Read-only integration checks against the actual teacher PDFs. Never calls DeepSeek. */
class RealCourseRetrievalTest {
    @TempDir static Path temporary;
    static CourseKnowledgeBase knowledge;
    static CourseKnowledgeBase.Report report;
    static Path courseDirectory;
    static Map<String, byte[]> hashes = new HashMap<>();

    @BeforeAll
    static void loadRealLectures() throws Exception {
        courseDirectory = Path.of(System.getProperty("coursemate.testCourseDir"));
        assertTrue(Files.isDirectory(courseDirectory), "Actual course_files folder is required for integration tests");
        try (var files = Files.list(courseDirectory)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".pdf")).toList()) {
                hashes.put(file.getFileName().toString(), MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
            }
        }
        knowledge = new CourseKnowledgeBase(courseDirectory, temporary.resolve("index.json"), new PdfCourseReader());
        report = knowledge.refresh(false);
    }

    @Test
    void actualLecturesAreReadableAndAssignmentExamAndScheduleAreExcluded() throws Exception {
        assertEquals(9, report.documents().size());
        assertEquals(199, report.documents().stream().mapToInt(CourseKnowledgeBase.FileIndex::pages).sum());
        assertTrue(report.warnings().isEmpty(), "All real lectures should have extractable text");
        assertTrue(report.chunkCount() >= 199);
        assertTrue(report.documents().stream().allMatch(document -> CourseKnowledgeBase.isLecture(document.name())));
        Files.createDirectories(Path.of("build/reports"));
        // Metadata only, never course text or credentials in the report/log.
        Files.writeString(Path.of("build/reports/rag-validation.txt"), report.summary());
    }

    @ParameterizedTest
    @CsvSource({"inheritance,Lec-2-OOP-with-Java.pdf", "泛型,Lec-3.2-Generics-and-Collections.pdf",
            "JUnit assertions,Lec-3.4-JUnit.pdf", "RAG retrieval augmented generation,Lec-5-Building-AI-Powered-Java-Applications.pdf"})
    void knownCourseConceptsRetrieveActualPages(String question, String expectedFile) {
        var hits = knowledge.search(question);
        assertFalse(hits.isEmpty());
        assertTrue(hits.size() <= 4);
        assertTrue(hits.stream().anyMatch(chunk -> chunk.fileName().equals(expectedFile)), "Expected real lecture must appear among results");
        for (CourseChunk hit : hits) {
            var file = report.documents().stream().filter(document -> document.name().equals(hit.fileName())).findFirst().orElseThrow();
            assertTrue(Files.isRegularFile(courseDirectory.resolve(hit.fileName())));
            assertTrue(hit.page() >= 1 && hit.page() <= file.pages());
            assertTrue(file.chunks().contains(hit), "Source must be an extracted real chunk, not model metadata");
        }
    }

    @Test
    void unrelatedQuestionHasNoSourcesAndAllOriginalPdfsRemainUnchanged() throws Exception {
        assertTrue(knowledge.search("quasarzz astronomyzz").isEmpty());
        assertTrue(knowledge.search("请解释量子纠缠实验中的光子偏振测量。").isEmpty());
        for (var before : hashes.entrySet()) {
            byte[] after = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(courseDirectory.resolve(before.getKey())));
            assertTrue(Arrays.equals(before.getValue(), after), "Original course PDF must remain unchanged");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Explain quantum entanglement and photon polarization measurements in simple terms.",
            "Explain quantum entanglement and photon polarization measurements.",
            "Explain lunar geology and volcanic craters in simple terms."})
    void unrelatedEnglishTopicsCannotUseJavaPagesAsEvidence(String question) {
        assertTrue(knowledge.search(question).isEmpty(), "Unrelated English topic must not retrieve Java evidence");
    }

    @Test
    void reviewedInheritanceAndGenericsResultsContainTeachingMaterialOnly() {
        // These physical PDF pages were read during the 0.3.0 runtime regression review.
        Set<String> inheritancePages = Set.of("Lec-2-OOP-with-Java.pdf:19", "Lec-2-OOP-with-Java.pdf:30",
                "Lec-3.2-Generics-and-Collections.pdf:8");
        for (String question : List.of("Java inheritance", "继承")) {
            var hits = knowledge.search(question);
            assertEquals("Lec-2-OOP-with-Java.pdf:19", hits.getFirst().fileName() + ":" + hits.getFirst().page());
            assertTrue(hits.stream().allMatch(hit -> inheritancePages.contains(hit.fileName() + ":" + hit.page())));
            assertTrue(hits.stream().allMatch(hit -> hit.text().toLowerCase(Locale.ROOT).contains("inheritance")));
        }
        var generics = knowledge.search("Java generics");
        assertFalse(generics.isEmpty());
        assertTrue(generics.stream().allMatch(hit -> hit.fileName().equals("Lec-3.2-Generics-and-Collections.pdf")
                && Set.of(3, 4, 8).contains(hit.page())));
    }
}
