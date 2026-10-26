package edu.t07.coursemate.rag;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.ArrayList;

/** Immutable evidence; offsets refer to preserved normalized page text, not PDF glyph coordinates. */
public record CourseChunk(String fileName, int page, String text, String chunkId, String documentHash,
                          int startOffset, int endOffset, String title, String blockType,
                          String previousId, String nextId) {
    public CourseChunk(String fileName, int page, String text) {
        this(fileName, page, text, "", "", 0, text == null ? 0 : text.length(), "", "TEXT", "", "");
    }
    public CourseChunk {
        if (fileName == null || fileName.isBlank() || page < 1 || text == null || text.isBlank()
                || startOffset < 0 || endOffset - startOffset != text.length()) throw new IllegalArgumentException("Invalid course chunk metadata");
        documentHash = documentHash == null ? "" : documentHash;
        String expected = id(fileName, page, startOffset, text, documentHash);
        if (chunkId == null || chunkId.isEmpty()) chunkId = expected;
        else if (!expected.equals(chunkId)) throw new IllegalArgumentException("Chunk fingerprint mismatch");
        title = title == null ? "" : title;
        blockType = blockType == null ? "TEXT" : blockType;
        previousId = previousId == null ? "" : previousId;
        nextId = nextId == null ? "" : nextId;
    }
    private static String id(String file, int page, int start, String text, String hash) {
        try {
            return "c-" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((file + "\0" + hash + "\0" + page + "\0" + start + "\0" + text).getBytes(StandardCharsets.UTF_8))).substring(0, 24);
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    public static List<CourseChunk> bindDocument(List<CourseChunk> chunks, String hash) {
        List<CourseChunk> result = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            CourseChunk c = chunks.get(i);
            String before = i == 0 ? "" : boundId(chunks.get(i - 1), hash);
            String after = i + 1 == chunks.size() ? "" : boundId(chunks.get(i + 1), hash);
            result.add(new CourseChunk(c.fileName, c.page, c.text, "", hash, c.startOffset, c.endOffset,
                    c.title, c.blockType, before, after));
        }
        return List.copyOf(result);
    }
    private static String boundId(CourseChunk c, String hash) { return id(c.fileName, c.page, c.startOffset, c.text, hash); }
    public static CourseChunk merge(CourseChunk left, CourseChunk right) {
        if (left.startOffset > right.startOffset) return merge(right, left);
        if (!left.fileName.equals(right.fileName) || left.page != right.page || !left.documentHash.equals(right.documentHash)
                || right.startOffset > left.endOffset || right.endOffset <= left.endOffset) return null;
        int overlap = left.endOffset - right.startOffset;
        if (overlap > right.text.length() || !left.text.regionMatches(right.startOffset-left.startOffset, right.text, 0, overlap)) return null;
        return new CourseChunk(left.fileName,left.page,left.text+right.text.substring(overlap),"",left.documentHash,
                left.startOffset,right.endOffset,left.title,left.blockType,left.previousId,right.nextId);
    }
}
