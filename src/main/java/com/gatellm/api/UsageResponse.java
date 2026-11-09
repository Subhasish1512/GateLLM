package com.gatellm.api;

public record UsageResponse(
        String apiKeyId,
        String date,
        long requests,
        long inputTokens,
        long outputTokens,
        long dailyTokenBudget,
        long tokensRemaining,
        int rateLimitPerMinute,
        long rateLimitRemaining,
        String budgetResetsAt
) {}
