package com.gatellm.llm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.gatellm.config.GateLlmProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;

@Component
public class AnthropicProvider implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(AnthropicProvider.class);
    private static final String API_BASE_URL = "https://api.anthropic.com";
    private static final String ANTHROPIC_VERSION = "2023-06-01";

    private final RestClient restClient;
    private final int maxOutputTokens;

    public AnthropicProvider(GateLlmProperties properties) {
        String apiKey = properties.anthropicApiKey();
        this.maxOutputTokens = properties.maxOutputTokens();

        if (apiKey != null && !apiKey.isBlank()) {
            var factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(Duration.ofSeconds(properties.timeoutSeconds()));
            factory.setReadTimeout(Duration.ofSeconds(properties.timeoutSeconds()));

            this.restClient = RestClient.builder()
                    .baseUrl(API_BASE_URL)
                    .defaultHeader("anthropic-version", ANTHROPIC_VERSION)
                    .defaultHeader("x-api-key", apiKey)
                    .requestFactory(factory)
                    .build();
            log.info("AnthropicProvider initialized (baseUrl={})", API_BASE_URL);
        } else {
            this.restClient = null;
            log.debug("AnthropicProvider created without API key (will fail if called)");
        }
    }

    /** Package-private constructor for testing with a mock RestClient. */
    AnthropicProvider(RestClient restClient) {
        this(restClient, 1024);
    }

    /** Package-private constructor for testing with a mock RestClient and maxTokens. */
    AnthropicProvider(RestClient restClient, int maxOutputTokens) {
        this.restClient = restClient;
        this.maxOutputTokens = maxOutputTokens;
    }

    @Override
    public String name() {
        return "anthropic";
    }

    @Override
    public LlmResponse call(String modelId, String prompt, int maxOutputTokens) {
        if (restClient == null) {
            throw new LlmProviderException(
                    LlmProviderException.ErrorType.CLIENT_ERROR,
                    "Anthropic provider not configured: ANTHROPIC_API_KEY is missing");
        }

        log.debug("Calling Anthropic model '{}' (maxTokens={})", modelId, maxOutputTokens);

        try {
            var request = new MessagesRequest(
                    modelId,
                    Math.min(maxOutputTokens, this.maxOutputTokens),
                    List.of(new Message("user", prompt))
            );

            var response = restClient.post()
                    .uri("/v1/messages")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, (req, res) -> {
                        int statusCode = res.getStatusCode().value();
                        throw new HttpClientErrorException(HttpStatus.valueOf(statusCode),
                                "Anthropic 4xx error: " + res.getStatusText());
                    })
                    .onStatus(HttpStatusCode::is5xxServerError, (req, res) -> {
                        int statusCode = res.getStatusCode().value();
                        throw new HttpServerErrorException(HttpStatus.valueOf(statusCode),
                                "Anthropic 5xx error: " + res.getStatusText());
                    })
                    .body(MessagesResponse.class);

            if (response == null || response.content() == null || response.content().isEmpty()) {
                throw new LlmProviderException(LlmProviderException.ErrorType.UNKNOWN,
                        "Empty response from Anthropic API");
            }

            String answer = response.content().stream()
                    .filter(block -> "text".equals(block.type()))
                    .map(ContentBlock::text)
                    .collect(Collectors.joining());

            log.debug("Anthropic response: model={}, inputTokens={}, outputTokens={}",
                    response.model(),
                    response.usage().inputTokens(),
                    response.usage().outputTokens());

            return new LlmResponse(
                    answer,
                    response.model(),
                    response.usage().inputTokens(),
                    response.usage().outputTokens()
            );
        } catch (HttpClientErrorException e) {
            LlmProviderException.ErrorType type = switch (e.getStatusCode().value()) {
                case 429 -> LlmProviderException.ErrorType.RATE_LIMITED;
                default -> LlmProviderException.ErrorType.CLIENT_ERROR;
            };
            throw new LlmProviderException(type, e.getStatusCode().value(),
                    "Anthropic client error: " + e.getMessage(), e);
        } catch (HttpServerErrorException e) {
            throw new LlmProviderException(LlmProviderException.ErrorType.UNAVAILABLE, e.getStatusCode().value(),
                    "Anthropic server error: " + e.getMessage(), e);
        } catch (ResourceAccessException e) {
            throw new LlmProviderException(LlmProviderException.ErrorType.UNAVAILABLE,
                    "Anthropic connection failed: " + e.getMessage(), e);
        } catch (RestClientException e) {
            throw new LlmProviderException(LlmProviderException.ErrorType.UNKNOWN,
                    "Anthropic API call failed: " + e.getMessage(), e);
        }
    }

    // ---- Anthropic Messages API DTOs (package-private for test access) ----

    record MessagesRequest(
            String model,
            @JsonProperty("max_tokens") int maxTokens,
            List<Message> messages
    ) {}

    record Message(String role, String content) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record MessagesResponse(
            String model,
            List<ContentBlock> content,
            Usage usage
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ContentBlock(String type, String text) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Usage(
            @JsonProperty("input_tokens") int inputTokens,
            @JsonProperty("output_tokens") int outputTokens
    ) {}
}