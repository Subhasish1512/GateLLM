package com.gatellm.ratelimit;

/**
 * Tracks per-apiKeyId daily usage and token budget in Redis.
 */
public interface UsageService {

    /** Total tokens (input + output) consumed by this key today. */
    long getTokensUsedToday(String apiKeyId);

    /**
     * Atomically record a successful LLM call's token usage.
     *
     * @return new total tokens consumed today (input + output)
     */
    long recordUsage(String apiKeyId, int inputTokens, int outputTokens);

    /** Full usage stats for the /usage endpoint. */
    UsageStats getUsageStats(String apiKeyId);

    record UsageStats(long requests, long inputTokens, long outputTokens) {}
}
