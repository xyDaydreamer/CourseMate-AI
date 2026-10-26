package edu.t07.coursemate.chat;

import edu.t07.coursemate.ai.ModelResponse;
import edu.t07.coursemate.rag.CourseChunk;
import edu.t07.coursemate.rag.RetrievalResult;
import java.util.*;

/** Immutable per-turn result. Source snapshots never change when the current index or language changes. */
public record AnswerResult(UUID turnId, List<Claim> claims, String generalAnswer, List<Source> sources,
                           RetrievalResult retrieval, Completion completion, ModelResponse model,
                           List<String> warnings) {
    public enum Completion { COMPLETE, TRUNCATED }
    public enum Basis { GENERAL, COURSE, MIXED, CANDIDATES_UNUSED }
    public record Claim(String text, List<String> sourceIds) { public Claim { sourceIds = List.copyOf(sourceIds); } }
    public record Source(String id, CourseChunk chunk) { }
    public AnswerResult { claims = List.copyOf(claims); sources = List.copyOf(sources); warnings = List.copyOf(warnings); }
    public Basis basis() { return claims.isEmpty() ? retrieval.evidence().isEmpty() ? Basis.GENERAL : Basis.CANDIDATES_UNUSED
            : generalAnswer.isBlank() ? Basis.COURSE : Basis.MIXED; }
    public String prose() {
        List<String> parts = new ArrayList<>(claims.stream().map(Claim::text).toList());
        if (!generalAnswer.isBlank()) parts.add(generalAnswer);
        return String.join("\n\n", parts);
    }
}
