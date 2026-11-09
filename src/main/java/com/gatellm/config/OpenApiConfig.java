package com.gatellm.config;

import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@SecurityScheme(
        name = "apiKey",
        type = SecuritySchemeType.APIKEY,
        in = io.swagger.v3.oas.annotations.enums.SecuritySchemeIn.HEADER,
        paramName = "X-API-Key",
        description = "GateLLM API Key for authentication"
)
public class OpenApiConfig {

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .components(new Components()
                        .addSecuritySchemes("apiKey",
                                new io.swagger.v3.oas.models.security.SecurityScheme()
                                        .type(io.swagger.v3.oas.models.security.SecurityScheme.Type.APIKEY)
                                        .in(io.swagger.v3.oas.models.security.SecurityScheme.In.HEADER)
                                        .name("X-API-Key")
                                        .description("GateLLM API Key for authentication")
                        ))
                .info(new Info()
                        .title("GateLLM Gateway API")
                        .version("1.0.0")
                        .description("""
                                GateLLM is a Spring Boot gateway in front of LLM providers (starting with Anthropic's Claude API)
                                that handles cost, abuse prevention, and reliability.

                                **Features:**
                                * API Key authentication (X-API-Key header)
                                * Per-key rate limiting (sliding window)
                                * Daily token budget with UTC midnight reset
                                * PII redaction (emails, phone numbers)
                                * Provider fallback on failure (429, 5xx, timeouts)
                                * Usage tracking and metrics (Prometheus)
                                * OpenAPI 3.1 documentation (Swagger UI)

                                **Endpoints:**
                                * `POST /chat` - Chat completion with gateway features
                                * `GET /usage` - Current day's usage statistics
                                * `GET /actuator/prometheus` - Prometheus metrics
                                * `GET /actuator/health` - Health check
                                * `GET /swagger-ui.html` - Interactive API docs

                                **Headers returned by POST /chat:**
                                * `X-Tokens-Remaining` - Remaining token budget for today
                                * `X-RateLimit-Remaining` - Remaining requests in current minute window
                                * `Retry-After` (on 429) - Seconds until next request allowed
                                """)
                        .license(new License()
                                .name("Apache 2.0")
                                .url("https://www.apache.org/licenses/LICENSE-2.0.html"))
                );
    }
}