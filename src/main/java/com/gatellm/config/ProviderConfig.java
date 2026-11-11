package com.gatellm.config;

import com.gatellm.llm.AnthropicProvider;
import com.gatellm.llm.LlmProvider;
import com.gatellm.llm.MockLlmProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ProviderConfig {

    @Bean
    @ConditionalOnProperty(name = "gatellm.provider", havingValue = "mock", matchIfMissing = true)
    public LlmProvider mockProvider(GateLlmProperties properties) {
        return new MockLlmProvider(properties);
    }

    @Bean
    @ConditionalOnProperty(name = "gatellm.provider", havingValue = "anthropic", matchIfMissing = false)
    public LlmProvider anthropicProvider(GateLlmProperties properties) {
        return new AnthropicProvider(properties);
    }
}