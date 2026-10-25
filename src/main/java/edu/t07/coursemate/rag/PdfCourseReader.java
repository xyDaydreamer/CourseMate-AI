package edu.t07.coursemate.rag;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Read only. Extraction never saves or rewrites the input PDF. */
public class PdfCourseReader {
    public static final int CHUNK_SIZE = 1000;
    public static final int OVERLAP = 150;

    public Document read(Path file) throws IOException {
        try (PDDocument pdf = Loader.loadPDF(file.toFile())) {
            if (!pdf.getCurrentAccessPermission().canExtractContent()) {
                throw new IOException("PDF 禁止提取文字。");
            }
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            List<CourseChunk> chunks = new ArrayList<>();
            int textPages = 0;
            for (int page = 1; page <= pdf.getNumberOfPages(); page++) {
                if (Thread.currentThread().isInterrupted()) throw new IOException("索引已取消。");
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                List<CourseChunk> pageChunks = split(file.getFileName().toString(), page, stripper.getText(pdf));
                if (!pageChunks.isEmpty()) textPages++;
                chunks.addAll(pageChunks);
            }
            if (chunks.isEmpty()) {
                throw new IOException("没有可提取文字（可能是扫描件）；未建立索引，需要可复制文字的 PDF 或另做 OCR。");
            }
            return new Document(pdf.getNumberOfPages(), textPages, List.copyOf(chunks));
        }
    }

    public static List<CourseChunk> split(String name, int page, String raw) {
        String text = raw.replace('\u0000', ' ').replaceAll("\\s+", " ").strip();
        List<CourseChunk> chunks = new ArrayList<>();
        for (int start = 0; start < text.length();) {
            int end = Math.min(start + CHUNK_SIZE, text.length());
            if (end < text.length()) {
                int boundary = text.lastIndexOf(' ', end);
                if (boundary > start + CHUNK_SIZE / 2) end = boundary;
                if (end > start && Character.isHighSurrogate(text.charAt(end - 1))) end--;
            }
            String part = text.substring(start, end).strip();
            if (!part.isEmpty()) chunks.add(new CourseChunk(name, page, part));
            if (end == text.length()) break;
            start = end - OVERLAP;
            if (Character.isLowSurrogate(text.charAt(start))) start++;
        }
        return List.copyOf(chunks);
    }

    public record Document(int pages, int textPages, List<CourseChunk> chunks) { }
}
