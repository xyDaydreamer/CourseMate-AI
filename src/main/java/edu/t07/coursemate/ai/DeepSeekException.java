package edu.t07.coursemate.ai;

/** Only safe, locally authored messages may be passed here. No raw API bodies or causes. */
public final class DeepSeekException extends Exception {
    public DeepSeekException(String safeMessage) {
        super(safeMessage);
    }
}
