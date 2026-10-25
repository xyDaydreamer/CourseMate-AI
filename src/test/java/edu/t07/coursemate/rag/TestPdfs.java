package edu.t07.coursemate.rag;

import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import java.io.IOException;
import java.nio.file.Path;

/** Synthetic fixtures written ONLY to JUnit temporary folders, never to course_files. */
final class TestPdfs {
    static Path write(Path path, String... pages) throws IOException {
        try (PDDocument document = new PDDocument()) {
            for (String text : pages) {
                PDPage page = new PDPage();
                document.addPage(page);
                if (text.isEmpty()) continue;
                try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                    stream.beginText();
                    stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    stream.newLineAtOffset(40, 700);
                    stream.showText(text);
                    stream.endText();
                }
            }
            document.save(path.toFile());
        }
        return path;
    }
}
