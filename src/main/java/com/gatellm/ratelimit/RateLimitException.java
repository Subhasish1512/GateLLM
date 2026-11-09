package com.gatellm.ratelimit;

/**
 * Thrown when a request is rejected due to rate-limit or token-budget exhaustion.
 * Mapped to HTTP 429 with a {@code Retry-After} header by the global exception handler.
 */
public class RateLimitException extends RuntimeException {

    public enum LimitType { RATE_LIMIT, BUDGET }

    private final LimitType limitType;
    private final long retryAfterSeconds;

    public RateLimitException(LimitType limitType, long retryAfterSeconds) {
        super(switch (limitType) {
            case RATE_LIMIT -> "Rate limit exceeded. Try again in " + retryAfterSeconds + " seconds.";
            case BUDGET     -> "Daily token budget exceeded. Resets at UTC midnight.";
        });
        this.limitType = limitType;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public LimitType getLimitType() {
        return limitType;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
