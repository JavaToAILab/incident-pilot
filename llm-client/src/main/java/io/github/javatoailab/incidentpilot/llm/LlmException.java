package io.github.javatoailab.incidentpilot.llm;

/**
 * A failed model call. The {@code retryable} flag is the important part: it is the
 * difference between "try again in a moment" and "fix the request".
 */
public class LlmException extends RuntimeException {

    private final boolean retryable;
    private final int statusCode;

    public LlmException(String message, boolean retryable, int statusCode, Throwable cause) {
        super(message, cause);
        this.retryable = retryable;
        this.statusCode = statusCode;
    }

    public LlmException(String message, boolean retryable, int statusCode) {
        this(message, retryable, statusCode, null);
    }

    public boolean isRetryable() {
        return retryable;
    }

    /** HTTP status, or -1 when the call never got a response (timeout, connection refused). */
    public int statusCode() {
        return statusCode;
    }

    /** 408, 429 and 5xx are worth retrying; other 4xx mean the request itself is wrong. */
    static boolean isRetryableStatus(int status) {
        return status == 408 || status == 429 || status >= 500;
    }
}
