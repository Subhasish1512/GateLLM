package com.gatellm.llm;

import com.gatellm.config.GateLlmProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Random;

public class MockLlmProvider implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(MockLlmProvider.class);

    private final long latencyMs;
    private final double failureRate;
    private final Random random;

    public MockLlmProvider(GateLlmProperties properties) {
        this.latencyMs = properties.mockLatencyMs();
        this.failureRate = properties.mockFailureRate();
        this.random = new Random();
        log.info("MockLlmProvider initialized: latency={}ms, failureRate={}", latencyMs, failureRate);
    }

    /** Package-private constructor for testing. */
    MockLlmProvider(long latencyMs, double failureRate) {
        this.latencyMs = latencyMs;
        this.failureRate = failureRate;
        this.random = new Random();
    }

    @Override
    public String name() {
        return "mock";
    }

    @Override
    public LlmResponse call(String modelId, String prompt, int maxOutputTokens) {
        if (random.nextDouble() < failureRate) {
            log.warn("Mock provider simulating failure for model '{}'", modelId);
            throw new LlmProviderException(
                    LlmProviderException.ErrorType.UNAVAILABLE,
                    "Mock provider simulated failure");
        }

        if (latencyMs > 0) {
            try {
                Thread.sleep(latencyMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new LlmProviderException(
                        LlmProviderException.ErrorType.UNKNOWN,
                        "Mock provider interrupted", e);
            }
        }

        int estimatedInputTokens = Math.max(1, prompt.length() / 4);
        int estimatedOutputTokens = Math.min(50, maxOutputTokens);

        log.debug("Mock response for model '{}': inputTokens={}, outputTokens={}, maxOutputTokens={}",
                modelId, estimatedInputTokens, estimatedOutputTokens, maxOutputTokens);

        return new LlmResponse(
                "This is a mock response for model: " + modelId,
                modelId,
                estimatedInputTokens,
                estimatedOutputTokens
        );
    }
}
