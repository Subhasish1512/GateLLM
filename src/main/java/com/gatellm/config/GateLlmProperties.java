package com.gatellm.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

@ConfigurationProperties(prefix = "gatellm")
public record GateLlmProperties(
        @DefaultValue("mock") String provider,
        @DefaultValue("") String anthropicApiKey,
        @DefaultValue("1024") int maxOutputTokens,
        @DefaultValue("30") int timeoutSeconds,
        @DefaultValue List<TargetProperties> targets,
        @DefaultValue List<ApiKeyProperties> apiKeys,
        @DefaultValue("200") long mockLatencyMs,
        @DefaultValue("0.5") double mockFailureRate
) {

    public record TargetProperties(
            String provider,
            String model
    ) {}

    public record ApiKeyProperties(
            String apiKeyId,
            String keyHash,
            @DefaultValue("10") int rateLimitPerMinute,
            @DefaultValue("100000") int dailyTokenBudget
    ) {}
}
