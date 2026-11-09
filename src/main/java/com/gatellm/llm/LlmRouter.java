package com.gatellm.llm;

import com.gatellm.config.GateLlmProperties;
import com.gatellm.metrics.GatewayMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class LlmRouter {

    private static final Logger log = LoggerFactory.getLogger(LlmRouter.class);

    private final Map<String, LlmProvider> providerMap;
    private final GateLlmProperties properties;
    private final GatewayMetrics metrics;

    public LlmRouter(List<LlmProvider> providers, GateLlmProperties properties, GatewayMetrics metrics) {
        this.providerMap = providers.stream()
                .collect(Collectors.toMap(LlmProvider::name, Function.identity()));
        this.properties = properties;
        this.metrics = metrics;
        log.info("LLM Router initialized with providers: {}, active: '{}'",
                providerMap.keySet(), properties.provider());
    }

    /**
     * Route the prompt through the ordered target list, falling back on retryable errors.
     * Retryable: rate-limited (429), server errors (5xx), timeouts, connection failures.
     * Non-retryable: client errors (4xx except 429) - fail immediately.
     *
     * @param prompt          the user prompt
     * @param maxOutputTokens capped token limit (may be reduced by budget)
     * @param apiKeyId        the authenticated API key ID for metrics
     */
    public LlmResponse route(String prompt, int maxOutputTokens, String apiKeyId) {
        List<GateLlmProperties.TargetProperties> targets = properties.targets();
        if (targets == null || targets.isEmpty()) {
            throw new LlmProviderException(
                    LlmProviderException.ErrorType.CLIENT_ERROR,
                    "No LLM targets configured");
        }

        boolean useMock = "mock".equalsIgnoreCase(properties.provider());
        LlmProviderException lastException = null;
        int attempt = 0;

        for (var target : targets) {
            String providerName = useMock ? "mock" : target.provider();
            LlmProvider provider = providerMap.get(providerName);

            if (provider == null) {
                log.warn("Provider '{}' not found, skipping target '{}'",
                        providerName, target.model());
                continue;
            }

            attempt++;
            try {
                log.debug("Routing to {}/{} (maxTokens={})", providerName, target.model(), maxOutputTokens);
                LlmResponse response = provider.call(target.model(), prompt, maxOutputTokens);
                log.debug("Got response from {}/{}", providerName, target.model());

                // Record fallback if this wasn't the first attempt
                if (attempt > 1) {
                    metrics.fallbackCount(target.model(), apiKeyId).increment();
                }
                return response;
            } catch (LlmProviderException e) {
                log.warn("Target {}/{} failed: {} (type={}, retryable={})",
                        providerName, target.model(), e.getMessage(),
                        e.getErrorType(), e.isRetryable());
                lastException = e;

                // Record provider error
                metrics.providerErrorCount(target.model(), apiKeyId, e.getErrorType().name()).increment();

                // Only fallback on retryable errors
                if (!e.isRetryable()) {
                    log.error("Non-retryable error from {}/{}, stopping fallback chain",
                            providerName, target.model());
                    throw e;
                }
            } catch (Exception e) {
                log.warn("Unexpected error from {}/{}: {}",
                        providerName, target.model(), e.getMessage());
                lastException = new LlmProviderException(
                        LlmProviderException.ErrorType.UNKNOWN,
                        "Provider error: " + e.getMessage(), e);
                // Record provider error for unexpected exceptions
                metrics.providerErrorCount(target.model(), apiKeyId, "UNKNOWN").increment();
                // Unknown errors are treated as retryable
            }
        }

        // All targets exhausted - wrap with retryable context
        throw new LlmProviderException(
                LlmProviderException.ErrorType.UNAVAILABLE,
                "All LLM targets exhausted. Last error: " +
                        (lastException != null ? lastException.getMessage() : "unknown"),
                lastException);
    }
}
