package com.gatellm.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.HistogramSnapshot;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Centralized Micrometer meters for the gateway.
 * All meters are tagged with {@code model} and {@code apiKeyId}.
 * Meters are cached to avoid creating duplicate instances on every call.
 */
@Component
public class GatewayMetrics {

    private final MeterRegistry registry;

    // Caches for meter instances (key = "model|apiKeyId" or "model|apiKeyId|errorType")
    private final Map<String, Counter> requestCountCache = new ConcurrentHashMap<>();
    private final Map<String, Counter> rateLimitedCountCache = new ConcurrentHashMap<>();
    private final Map<String, Counter> budgetExceededCountCache = new ConcurrentHashMap<>();
    private final Map<String, Counter> fallbackCountCache = new ConcurrentHashMap<>();
    private final Map<String, Counter> providerErrorCountCache = new ConcurrentHashMap<>();
    private final Map<String, Counter> inputTokensCache = new ConcurrentHashMap<>();
    private final Map<String, Counter> outputTokensCache = new ConcurrentHashMap<>();
    private final Map<String, Timer> latencyTimerCache = new ConcurrentHashMap<>();

    public GatewayMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    // ----- Counters -----

    public Counter requestCount(String model, String apiKeyId) {
        String key = cacheKey(model, apiKeyId);
        return requestCountCache.computeIfAbsent(key, k ->
                Counter.builder("gateway.requests.total")
                        .tags(tags(model, apiKeyId))
                        .description("Total number of chat requests")
                        .register(registry));
    }

    public Counter rateLimitedCount(String model, String apiKeyId) {
        String key = cacheKey(model, apiKeyId);
        return rateLimitedCountCache.computeIfAbsent(key, k ->
                Counter.builder("gateway.rate_limited.total")
                        .tags(tags(model, apiKeyId))
                        .description("Total requests rejected by rate limiter")
                        .register(registry));
    }

    public Counter budgetExceededCount(String model, String apiKeyId) {
        String key = cacheKey(model, apiKeyId);
        return budgetExceededCountCache.computeIfAbsent(key, k ->
                Counter.builder("gateway.budget_exceeded.total")
                        .tags(tags(model, apiKeyId))
                        .description("Total requests rejected due to daily token budget exhaustion")
                        .register(registry));
    }

    public Counter fallbackCount(String model, String apiKeyId) {
        String key = cacheKey(model, apiKeyId);
        return fallbackCountCache.computeIfAbsent(key, k ->
                Counter.builder("gateway.fallback.total")
                        .tags(tags(model, apiKeyId))
                        .description("Total times fallback to next target occurred")
                        .register(registry));
    }

    public Counter providerErrorCount(String model, String apiKeyId, String errorType) {
        String key = cacheKey(model, apiKeyId, errorType);
        return providerErrorCountCache.computeIfAbsent(key, k ->
                Counter.builder("gateway.provider_errors.total")
                        .tags(tags(model, apiKeyId))
                        .tag("error_type", errorType)
                        .description("Total provider errors by type")
                        .register(registry));
    }

    // ----- Timers -----

    public Timer latencyTimer(String model, String apiKeyId) {
        String key = cacheKey(model, apiKeyId);
        return latencyTimerCache.computeIfAbsent(key, k ->
                Timer.builder("gateway.latency")
                        .tags(tags(model, apiKeyId))
                        .description("End-to-end latency of /chat requests")
                        .publishPercentiles(0.5, 0.95, 0.99)
                        .register(registry));
    }

    public Timer.Sample startLatency() {
        return Timer.start(registry);
    }

    public void recordLatency(Timer.Sample sample, String model, String apiKeyId) {
        sample.stop(latencyTimer(model, apiKeyId));
    }

    // ----- Token Counters -----

    public void recordInputTokens(String model, String apiKeyId, int count) {
        if (count <= 0) return;
        String key = cacheKey(model, apiKeyId);
        Counter counter = inputTokensCache.computeIfAbsent(key, k ->
                Counter.builder("gateway.tokens.input")
                        .tags(tags(model, apiKeyId))
                        .description("Total input tokens consumed")
                        .register(registry));
        counter.increment(count);
    }

    public void recordOutputTokens(String model, String apiKeyId, int count) {
        if (count <= 0) return;
        String key = cacheKey(model, apiKeyId);
        Counter counter = outputTokensCache.computeIfAbsent(key, k ->
                Counter.builder("gateway.tokens.output")
                        .tags(tags(model, apiKeyId))
                        .description("Total output tokens generated")
                        .register(registry));
        counter.increment(count);
    }

    // ----- Helpers -----

    private static String cacheKey(String model, String apiKeyId) {
        return (model != null ? model : "unknown") + "|" + (apiKeyId != null ? apiKeyId : "unknown");
    }

    private static String cacheKey(String model, String apiKeyId, String errorType) {
        return cacheKey(model, apiKeyId) + "|" + (errorType != null ? errorType : "unknown");
    }

    private List<Tag> tags(String model, String apiKeyId) {
        return List.of(
                Tag.of("model", model != null ? model : "unknown"),
                Tag.of("apiKeyId", apiKeyId != null ? apiKeyId : "unknown")
        );
    }
}