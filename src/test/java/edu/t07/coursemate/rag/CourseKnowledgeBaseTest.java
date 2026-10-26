package edu.t07.coursemate.rag;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import static org.junit.jupiter.api.Assertions.*;

class CourseKnowledgeBaseTest {
    @TempDir Path temporary;

    @Test
    void oldFlattenedCacheIsRebuiltAndReloadedChunksAreImmutable() throws Exception {
        TestPdfs.write(temporary.resolve("Lec-1-Test.pdf"), "Generics improve type safety.");
        Path cache = temporary.resolve("index.json");
        CountingReader reader = new CountingReader();
        new CourseKnowledgeBase(temporary, cache, reader).refresh(false);
        String version3 = Files.readString(cache);
        assertTrue(version3.contains("\"version\":3"));
        Files.writeString(cache, version3.replace("\"version\":3", "\"version\":1"));
        new CourseKnowledgeBase(temporary, cache, reader).refresh(false);
        assertEquals(2, reader.calls);
        var report = new CourseKnowledgeBase(temporary, cache, reader).refresh(false);
        assertEquals(2, reader.calls);
        assertThrows(UnsupportedOperationException.class, () -> report.documents().getFirst().chunks().clear());
    }

    private static final class CountingReader extends PdfCourseReader {
        int calls;
        @Override public Document read(Path file) throws IOException { calls++; return super.read(file); }
    }

    @Test
    void reusesCacheAndReparsesOnlyChangesAndSupportsForcedRebuildAndRemoval() throws Exception {
        Path courses = Files.createDirectory(temporary.resolve("course_files"));
        Path cache = temporary.resolve("cache/index.json");
        Path file = TestPdfs.write(courses.resolve("Lec-2-OOP.pdf"), "Inheritance extends superclass.");
        CountingReader reader = new CountingReader();
        CourseKnowledgeBase kb = new CourseKnowledgeBase(courses, cache, reader);
        kb.refresh(false);
        assertEquals(1, reader.calls);
        kb.refresh(false);
        assertEquals(1, reader.calls, "Unchanged questions must not parse PDFs again");
        CourseKnowledgeBase reopened = new CourseKnowledgeBase(courses, cache, reader);
        reopened.refresh(false);
        assertEquals(1, reader.calls, "Restart should load the persisted cache");
        assertFalse(reopened.search("inheritance").isEmpty());
        Files.setLastModifiedTime(file, FileTime.fromMillis(Files.getLastModifiedTime(file).toMillis() + 5000));
        reopened.refresh(false);
        assertEquals(2, reader.calls);
        reopened.refresh(true);
        assertEquals(3, reader.calls);
        Files.delete(file); // Only a JUnit temporary fixture, never a real lecture.
        reopened.refresh(false);
        assertTrue(reopened.search("inheritance").isEmpty());
    }

    @Test
    void excludesAssignmentExamScheduleAndReportsBrokenAndBlankLectures() throws Exception {
        Path courses = Files.createDirectory(temporary.resolve("course_files"));
        TestPdfs.write(courses.resolve("Lec-3.2-Generics.pdf"), "Generics improve type safety in collections.");
        TestPdfs.write(courses.resolve("Lec-1-Blank.pdf"), "");
        Files.writeString(courses.resolve("Lec-2-Broken.pdf"), "invalid pdf");
        Files.writeString(courses.resolve("Assignment-A.pdf"), "invalid excluded file");
        Files.writeString(courses.resolve("Presentation-Schedule.pdf"), "invalid excluded file");
        Files.writeString(courses.resolve("Programming-Exam.pdf"), "invalid excluded file");
        var kb = new CourseKnowledgeBase(courses, temporary.resolve("index.json"), new PdfCourseReader());
        var report = kb.refresh(false);
        assertEquals(3, report.documents().size());
        assertEquals(2, report.warnings().size());
        assertTrue(report.summary().contains("未建立该文件的索引"));
        assertTrue(report.documents().stream().allMatch(file -> CourseKnowledgeBase.isLecture(file.name())));
        assertTrue(kb.search("generics").stream().allMatch(chunk -> chunk.fileName().equals("Lec-3.2-Generics.pdf")));
        assertFalse(CourseKnowledgeBase.isLecture("AY202627S1-CST3492-Assignment-A.pdf"));
        assertFalse(CourseKnowledgeBase.isLecture("Lec-6-Other.pdf"));
    }

    @Test
    void changedBrokenPdfDoesNotKeepOldSources() throws Exception {
        Path file = TestPdfs.write(temporary.resolve("Lec-2-OOP.pdf"), "Inheritance extends superclass.");
        var kb = new CourseKnowledgeBase(temporary, temporary.resolve("index.json"), new PdfCourseReader());
        kb.refresh(false);
        assertFalse(kb.search("inheritance").isEmpty());
        Files.writeString(file, "broken changed fixture");
        var report = kb.refresh(false);
        assertEquals(0, report.chunkCount());
        assertFalse(report.warnings().isEmpty());
        assertTrue(kb.search("inheritance").isEmpty());
    }

    @Test
    void missingDirectoryAndCorruptCacheHaveExplicitMessagesAndRecover() throws Exception {
        var missing = new CourseKnowledgeBase(temporary.resolve("missing"), temporary.resolve("missing-cache.json"), new PdfCourseReader());
        assertTrue(missing.refresh(false).summary().contains("无法读取课程目录"));
        TestPdfs.write(temporary.resolve("Lec-1-Test.pdf"), "Generics improve type safety.");
        Path cache = Files.writeString(temporary.resolve("index.json"), "broken cache");
        var kb = new CourseKnowledgeBase(temporary, cache, new PdfCourseReader());
        assertTrue(kb.refresh(false).summary().contains("缓存无法读取"));
        assertFalse(kb.search("generics").isEmpty());
    }
}
