package edu.t07.coursemate.rag;

import java.util.List;

/** Candidate facts and supplied evidence are separate from the model's eventual source choices. */
public record RetrievalResult(Status status, String resolvedQuery, List<Hit> candidates,
                              List<CourseChunk> evidence, List<String> uncoveredTopics, String indexVersion) {
    public enum Status { NO_HITS, INSUFFICIENT, READY, PARTIAL, KNOWLEDGE_UNAVAILABLE }
    public record Hit(CourseChunk chunk, double bm25, double score, double coverage) { }
    public RetrievalResult {
        candidates = List.copyOf(candidates); evidence = List.copyOf(evidence);
        uncoveredTopics = List.copyOf(uncoveredTopics);
    }
}
