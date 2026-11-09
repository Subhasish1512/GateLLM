package com.gatellm.llm;

public record LlmResponse(
        String answer,
        String modelUsed,
        int inputTokens,
        int outputTokens
) {}
