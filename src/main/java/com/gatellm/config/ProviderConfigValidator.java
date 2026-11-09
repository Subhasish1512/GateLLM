package com.gatellm.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class ProviderConfigValidator {

    private static final Logger log = LoggerFactory.getLogger(ProviderConfigValidator.class);

    public ProviderConfigValidator(GateLlmProperties properties) {
        String provider = properties.provider();
        if ("anthropic".equalsIgnoreCase(provider)) {
            String apiKey = properties.anthropicApiKey();
            if (apiKey == null || apiKey.isBlank()) {
                throw new IllegalStateException(
                        "ANTHROPIC_API_KEY environment variable must be set when gatellm.provider=anthropic. " +
                        "Set GATELLM_PROVIDER=mock for testing without an API key."
                );
            }
            log.info("Anthropic provider configured successfully");
        } else {
            log.info("Using '{}' LLM provider", provider);
        }
    }
}
