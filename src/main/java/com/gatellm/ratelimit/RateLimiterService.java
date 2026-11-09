package com.gatellm.ratelimit;

/**
 * Checks and enforces per-apiKeyId request rate limits.
 */
public interface RateLimiterService {

    /**
     * Atomically check and increment the rate-limit counter.
     *
     * @return result indicating whether the request is allowed plus remaining quota
     */
    RateLimitResult checkRateLimit(String apiKeyId, int limit, int windowSeconds);

    /**
     * Read the remaining requests without consuming one (for the /usage endpoint).
     */
    long getRemainingRequests(String apiKeyId, int limit);

    record RateLimitResult(boolean allowed, long remaining, long retryAfterSeconds) {}
}
