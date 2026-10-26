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
        String text = raw.replace("\r\n", "\n").replace('\r', '\n').replace('\f', '\n')
                .replace('\u0000', ' ').replace('\u00a0', ' ')
                .replaceAll("[ \\t]+(?=\\n|$)", "").replaceAll("\\n{3,}", "\n\n").stripTrailing();
        List<CourseChunk> chunks = new ArrayList<>();
        for (int start = 0; start < text.length();) {
            int end = Math.min(start + CHUNK_SIZE, text.length());
            if (end < text.length()) {
                int boundary = lineBoundary(text, start, end);
                if (boundary <= start + CHUNK_SIZE / 2) boundary = text.lastIndexOf(' ', end);
                if (boundary > start + CHUNK_SIZE / 2) end = boundary;
                if (end > start && Character.isHighSurrogate(text.charAt(end - 1))) end--;
            }
            String part = text.substring(start, end).stripTrailing();
            if (!part.isBlank()) {
                String title = text.lines().filter(line -> !line.isBlank()).findFirst().orElse("");
                if (title.length() > 120) title = title.substring(0, 120);
                String kind = part.matches("(?s).*(?:[;{}]|//|class\\s+).*" ) ? "CODE_OR_MIXED" : "TEXT";
                chunks.add(new CourseChunk(name, page, part, "", "", start, start + part.length(), title, kind, "", ""));
            }
            if (end == text.length()) break;
            start = end - OVERLAP;
            int lineStart = text.lastIndexOf('\n', start);
            if (lineStart >= 0 && lineStart + 1 > end - CHUNK_SIZE / 2) start = lineStart + 1;
            if (Character.isLowSurrogate(text.charAt(start))) start++;
        }
        return List.copyOf(chunks);
    }

    /** Prefer a paragraph/complete brace block, then a full line. Oversized blocks still obey the size limit. */
    private static int lineBoundary(String text, int start, int end) {
        int depth = 0, complete = -1, line = -1;
        for (int i = start; i < end; i++) {
            char c = text.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') depth = Math.max(0, depth - 1);
            else if (c == '\n' && i > start + CHUNK_SIZE / 2) {
                line = i;
                if (depth == 0) complete = i;
            }
        }
        return complete >= 0 ? complete : line;
    }

    public record Document(int pages, int textPages, List<CourseChunk> chunks) { }
}
