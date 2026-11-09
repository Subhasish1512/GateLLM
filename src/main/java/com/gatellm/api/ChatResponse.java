package com.gatellm.api;

public record ChatResponse(
        String answer,
        String modelUsed,
        int inputTokens,
        int outputTokens,
        long latencyMs
) {}
