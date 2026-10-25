package edu.t07.coursemate.rag;

/** Page numbers are physical PDF pages, starting at 1, never inferred slide numbers. */
public record CourseChunk(String fileName, int page, String text) {
    public CourseChunk {
        if (fileName == null || fileName.isBlank() || page < 1 || text == null || text.isBlank()) {
            throw new IllegalArgumentException("Invalid course chunk metadata");
        }
    }
}
