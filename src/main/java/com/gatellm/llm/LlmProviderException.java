package com.gatellm.llm;

/**
 * Exception thrown by LLM providers.
 * The {@code ErrorType} allows the router to decide whether to retry with the next target.
 */
public class LlmProviderException extends RuntimeException {

    public enum ErrorType {
        /** Provider returned 429 or explicitly indicated rate limiting */
        RATE_LIMITED,
        /** Provider returned 5xx or is unreachable (timeout, connection refused) */
        UNAVAILABLE,
        /** Provider returned 4xx (bad request, invalid key, etc.) - don't retry */
        CLIENT_ERROR,
        /** Unexpected error - may retry */
        UNKNOWN
    }

    private final ErrorType errorType;
    private final int httpStatus;

    public LlmProviderException(ErrorType errorType, String message) {
        super(message);
        this.errorType = errorType;
        this.httpStatus = 0;
    }

    public LlmProviderException(ErrorType errorType, String message, Throwable cause) {
        super(message, cause);
        this.errorType = errorType;
        this.httpStatus = 0;
    }

    public LlmProviderException(ErrorType errorType, int httpStatus, String message) {
        super(message);
        this.errorType = errorType;
        this.httpStatus = httpStatus;
    }

    public LlmProviderException(ErrorType errorType, int httpStatus, String message, Throwable cause) {
        super(message, cause);
        this.errorType = errorType;
        this.httpStatus = httpStatus;
    }

    public ErrorType getErrorType() {
        return errorType;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    /** Whether this error should trigger a fallback to the next target */
    public boolean isRetryable() {
        return errorType == ErrorType.RATE_LIMITED || errorType == ErrorType.UNAVAILABLE;
    }

    // Backwards compatibility constructors
    public LlmProviderException(String message) {
        this(ErrorType.UNKNOWN, message);
    }

    public LlmProviderException(String message, Throwable cause) {
        this(ErrorType.UNKNOWN, message, cause);
    }
}
