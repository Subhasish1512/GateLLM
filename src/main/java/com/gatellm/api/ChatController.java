package com.gatellm.api;

import com.gatellm.llm.LlmResponse;
import com.gatellm.llm.LlmRouter;
import com.gatellm.metrics.GatewayMetrics;
import com.gatellm.ratelimit.RateLimitException;
import com.gatellm.ratelimit.RateLimiterService;
import com.gatellm.ratelimit.UsageService;
import com.gatellm.security.AuthenticatedKeyResolver;
import com.gatellm.security.PiiRedactor;
import io.micrometer.core.instrument.Timer;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

@RestController
@Tag(name = "Chat", description = "LLM chat completion endpoint")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final LlmRouter router;
    private final RateLimiterService rateLimiterService;
    private final UsageService usageService;
    private final AuthenticatedKeyResolver keyResolver;
    private final PiiRedactor piiRedactor;
    private final GatewayMetrics metrics;
    private final int configuredMaxOutputTokens;

    public ChatController(LlmRouter router,
                          RateLimiterService rateLimiterService,
                          UsageService usageService,
                          AuthenticatedKeyResolver keyResolver,
                          PiiRedactor piiRedactor,
                          GatewayMetrics metrics,
                          @Value("${gatellm.max-output-tokens:1024}") int configuredMaxOutputTokens) {
        this.router = router;
        this.rateLimiterService = rateLimiterService;
        this.usageService = usageService;
        this.keyResolver = keyResolver;
        this.piiRedactor = piiRedactor;
        this.metrics = metrics;
        this.configuredMaxOutputTokens = configuredMaxOutputTokens;
    }

    @PostMapping("/chat")
    @Operation(summary = "Send a chat completion request")
    public ResponseEntity<?> chat(@Valid @org.springframework.web.bind.annotation.RequestBody ChatRequest request) {

        log.debug("Received request: prompt='{}', prompt.length={}", request.getPrompt(),
                request.getPrompt() != null ? request.getPrompt().length() : -1);
        log.debug("Request object: {}", request);

        if (request.getPrompt() == null || request.getPrompt().trim().isEmpty()) {
            log.warn("Prompt validation failed: prompt is null or empty");
            return ResponseEntity.badRequest().body(new ErrorResponse(400, "Prompt must not be blank"));
        }

        var keyProps = keyResolver.getKeyProperties();
        String apiKeyId = keyProps.apiKeyId();

        log.debug("Chat request from '{}' (prompt length: {} chars)",
                apiKeyId, request.getPrompt().length());

        // Start latency timer
        Timer.Sample latencySample = metrics.startLatency();

        // 1. Rate-limit check (consumes one slot)
        var rateResult = rateLimiterService.checkRateLimit(
                apiKeyId, keyProps.rateLimitPerMinute(), 60);
        if (!rateResult.allowed()) {
            metrics.rateLimitedCount(null, apiKeyId).increment();
            metrics.recordLatency(latencySample, "rate-limited", apiKeyId);
            throw new RateLimitException(
                    RateLimitException.LimitType.RATE_LIMIT, rateResult.retryAfterSeconds());
        }

        // 2. Budget pre-check
        long tokensUsed = usageService.getTokensUsedToday(apiKeyId);
        long budgetRemaining = keyProps.dailyTokenBudget() - tokensUsed;
        if (budgetRemaining <= 0) {
            metrics.budgetExceededCount(null, apiKeyId).increment();
            metrics.recordLatency(latencySample, "budget-exceeded", apiKeyId);
            throw new RateLimitException(
                    RateLimitException.LimitType.BUDGET, secondsUntilMidnightUtc());
        }

        // 3. PII redaction - mask emails and phones before sending to LLM
        String redactedPrompt = piiRedactor.redact(request.getPrompt());

        // 4. Cap maxOutputTokens so a single request cannot overshoot far
        int cappedMaxTokens = (int) Math.min(configuredMaxOutputTokens, budgetRemaining);

        // 5. Call the LLM (failed calls are NOT charged)
        long startNanos = System.nanoTime();
        LlmResponse llmResponse = router.route(redactedPrompt, cappedMaxTokens, apiKeyId);
        long latencyMs = (System.nanoTime() - startNanos) / 1_000_000;
        metrics.recordLatency(latencySample, llmResponse.modelUsed(), apiKeyId);

        // Record request count
        metrics.requestCount(llmResponse.modelUsed(), apiKeyId).increment();

        // Record token usage
        metrics.recordInputTokens(llmResponse.modelUsed(), apiKeyId, llmResponse.inputTokens());
        metrics.recordOutputTokens(llmResponse.modelUsed(), apiKeyId, llmResponse.outputTokens());

        // 6. Record real token usage in Redis
        long totalTokensUsed = usageService.recordUsage(
                apiKeyId, llmResponse.inputTokens(), llmResponse.outputTokens());
        long newBudgetRemaining = Math.max(0, keyProps.dailyTokenBudget() - totalTokensUsed);

        // 7. Build response with quota headers
        var chatResponse = new ChatResponse(
                llmResponse.answer(),
                llmResponse.modelUsed(),
                llmResponse.inputTokens(),
                llmResponse.outputTokens(),
                latencyMs
        );

        log.debug("Chat response: model={}, latency recorded, tokensRemaining={}",
                chatResponse.modelUsed(), newBudgetRemaining);

        return ResponseEntity.ok()
                .header("X-Tokens-Remaining", String.valueOf(newBudgetRemaining))
                .header("X-RateLimit-Remaining", String.valueOf(rateResult.remaining()))
                .body(chatResponse);
    }

    private static long secondsUntilMidnightUtc() {
        Instant now = Instant.now();
        Instant midnight = LocalDate.now(ZoneOffset.UTC)
                .plusDays(1)
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant();
        return Duration.between(now, midnight).getSeconds();
    }
}