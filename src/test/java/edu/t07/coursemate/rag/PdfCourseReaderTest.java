package edu.t07.coursemate.rag;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

class PdfCourseReaderTest {
    @TempDir Path temporary;

    @Test
    void parsesPagesWithOriginalNameAndOneBasedPageNumbersWithoutChangingPdf() throws Exception {
        Path file = TestPdfs.write(temporary.resolve("Lec-2-Fixture.pdf"),
                "Encapsulation hides implementation details.", "Inheritance extends a superclass.");
        byte[] before = Files.readAllBytes(file);
        var document = new PdfCourseReader().read(file);
        assertEquals(2, document.pages());
        assertEquals(2, document.textPages());
        assertEquals("Lec-2-Fixture.pdf", document.chunks().get(0).fileName());
        assertEquals(1, document.chunks().get(0).page());
        assertEquals(2, document.chunks().get(1).page());
        assertTrue(document.chunks().get(1).text().contains("Inheritance"));
        assertTrue(Arrays.equals(before, Files.readAllBytes(file)), "Input PDF must be unchanged");
    }

    @Test
    void splittingRetainsMetadataAndBoundsAndOverlap() {
        String text = "inheritance encapsulation polymorphism ".repeat(120);
        var chunks = PdfCourseReader.split("Lec-2-Fixture.pdf", 8, text);
        assertTrue(chunks.size() > 2);
        assertTrue(chunks.stream().allMatch(chunk -> chunk.page() == 8 && chunk.fileName().equals("Lec-2-Fixture.pdf")
                && !chunk.text().isBlank() && chunk.text().length() <= 1000));
        String first = chunks.get(0).text();
        assertTrue(chunks.get(1).text().startsWith(first.substring(first.length() - 150).strip()));
        assertTrue(PdfCourseReader.split("Lec-2-Fixture.pdf", 1, " \n\t").isEmpty());
    }

    @Test
    void emptyTextAndUnreadableDocumentsFailExplicitly() throws Exception {
        Path blank = TestPdfs.write(temporary.resolve("Lec-1-Blank.pdf"), "");
        IOException noText = assertThrows(IOException.class, () -> new PdfCourseReader().read(blank));
        assertTrue(noText.getMessage().contains("没有可提取文字"));
        Path broken = Files.writeString(temporary.resolve("Lec-1-Broken.pdf"), "Not a PDF");
        assertThrows(IOException.class, () -> new PdfCourseReader().read(broken));
        assertThrows(IOException.class, () -> new PdfCourseReader().read(temporary.resolve("missing.pdf")));
    }

    @Test
    void skipsBlankPageWithoutShiftingFollowingPageNumber() throws Exception {
        var parsed = new PdfCourseReader().read(TestPdfs.write(temporary.resolve("Lec-1-Pages.pdf"),
                "", "Generics support type safety."));
        assertEquals(2, parsed.pages());
        assertEquals(1, parsed.textPages());
        assertEquals(2, parsed.chunks().getFirst().page());
    }
}
