package com.gatellm.api;

import com.gatellm.ratelimit.RateLimiterService;
import com.gatellm.ratelimit.UsageService;
import com.gatellm.security.AuthenticatedKeyResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * Returns the caller's own usage for the current UTC day.
 * Does NOT consume rate-limit or tokens.
 */
@RestController
@Tag(name = "Usage", description = "Usage tracking and quota information")
@SecurityRequirement(name = "apiKey")
public class UsageController {

    private static final Logger log = LoggerFactory.getLogger(UsageController.class);

    private final UsageService usageService;
    private final RateLimiterService rateLimiterService;
    private final AuthenticatedKeyResolver keyResolver;

    public UsageController(UsageService usageService,
                           RateLimiterService rateLimiterService,
                           AuthenticatedKeyResolver keyResolver) {
        this.usageService = usageService;
        this.rateLimiterService = rateLimiterService;
        this.keyResolver = keyResolver;
    }

    @GetMapping("/usage")
    @Operation(summary = "Get current day's usage statistics",
            description = "Returns usage stats for the authenticated API key for the current UTC day. Does not consume rate limit or token budget.",
            security = @SecurityRequirement(name = "apiKey"))
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Usage statistics retrieved successfully",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = UsageResponse.class))),
            @ApiResponse(responseCode = "401", description = "Invalid or missing API key",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<UsageResponse> getUsage() {
        var keyProps = keyResolver.getKeyProperties();
        String apiKeyId = keyProps.apiKeyId();

        var stats = usageService.getUsageStats(apiKeyId);
        long tokensUsed = stats.inputTokens() + stats.outputTokens();
        long tokensRemaining = Math.max(0, keyProps.dailyTokenBudget() - tokensUsed);
        long rateRemaining = rateLimiterService.getRemainingRequests(
                apiKeyId, keyProps.rateLimitPerMinute());

        String today = LocalDate.now(ZoneOffset.UTC).toString();
        String budgetResetsAt = LocalDate.now(ZoneOffset.UTC)
                .plusDays(1)
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant()
                .toString();

        log.debug("Usage query for '{}': tokens={}/{}, rate remaining={}",
                apiKeyId, tokensUsed, keyProps.dailyTokenBudget(), rateRemaining);

        return ResponseEntity.ok(new UsageResponse(
                apiKeyId,
                today,
                stats.requests(),
                stats.inputTokens(),
                stats.outputTokens(),
                keyProps.dailyTokenBudget(),
                tokensRemaining,
                keyProps.rateLimitPerMinute(),
                rateRemaining,
                budgetResetsAt
        ));
    }
}
