package edu.t07.coursemate.ai;

/** Safe user messages and bounded, process-only diagnostics are stored separately. */
public final class DeepSeekException extends Exception {
    public enum Kind { CONFIGURATION, NETWORK, TIMEOUT, HTTP, FORMAT, INVALID_CITATION, CANCELLED, INPUT_LIMIT, UNKNOWN }
    private final Kind kind;
    private final int status;
    private final transient Object diagnostic;
    public DeepSeekException(String safeMessage) {
        super(safeMessage);
        kind = Kind.UNKNOWN; status = 0; diagnostic = null;
    }
    public DeepSeekException(Kind kind, String safeMessage, int status, Object diagnostic) {
        super(safeMessage); this.kind = kind; this.status = status; this.diagnostic = diagnostic;
    }
    public Kind kind() { return kind; }
    public int status() { return status; }
    public Object diagnostic() { return diagnostic; }
}
