# GateLLM - Project Guide for Claude Code

## Project Overview
GateLLM is a Spring Boot gateway in front of LLM providers (starting with Anthropic's Claude API) that handles cost, abuse, and reliability.
Clients call `POST /chat` with a gateway API key (`X-API-Key`).
The gateway authenticates callers, rate-limits, enforces a daily token budget, redacts PII, routes to a cheap model first, falls back on failure, tracks usage, and exposes metrics.
There is **NO caching** and **NO embeddings** in this project.

## Allowed Tech Stack & Rules
- **Strictly Allowed Only**: Java 21, Spring Boot 3.x, Gradle (Groovy DSL, `build.gradle`, Gradle wrapper), Redis, Docker & docker-compose, Bash, JavaScript, React.js, JUnit 5, Mockito, k6, Micrometer, Prometheus, Grafana.
- **Forbidden**: Do NOT use Python, Kotlin, Maven, Lombok, or unrequested libraries/languages.
- **Architectural Rules**:
  - Constructor injection only (no `@Autowired` fields).
  - Single responsibility classes; interfaces where mocking is required.
  - Meaningful exceptions mapped to proper HTTP status codes (400, 401, 429, 502, 500).
  - SLF4J logging; never log raw API keys, full headers, or prompts. Mask keys (`test****key1`).
  - Unit tests required for all classes (JUnit 5 + Mockito).

## Package Layout (`com.gatellm`)
- `com.gatellm.api`: Controllers, DTOs (`ChatRequest`, `ChatResponse`, `UsageResponse`, `ErrorResponse`), `GlobalExceptionHandler`
- `com.gatellm.security`: `ApiKeyFilter`, `ApiKeyAuthentication`, `AuthenticatedKeyResolver`, `SecurityConfig`
- `com.gatellm.ratelimit`: `RedisRateLimiter`, `RedisUsageService`, `RateLimitException`
- `com.gatellm.llm`: `LlmProvider` interface, `AnthropicProvider`, `MockLlmProvider`, `LlmRouter` (ordered fallback)
- `com.gatellm.config`: `GateLlmProperties` (typed records), `ProviderConfigValidator`
- `com.gatellm.metrics`: (Upcoming phases) Micrometer metrics

## Current Implementation State
### Phase 1 (Completed):
- Gradle setup (`build.gradle`, `settings.gradle`, `gradlew`, `gradlew.bat`).
- Core gateway: `POST /chat`, `LlmRouter`, `AnthropicProvider`, `MockLlmProvider`.
- API Key authentication filter using SHA-256 constant-time comparison.
- Multi-stage `Dockerfile`, `docker-compose.yml`, `run-local.sh`, `.env.example`, `.gitignore`.
- Tests for `ChatControllerTest`, `ApiKeyFilterTest`, `AnthropicProviderTest`, `MockLlmProviderTest`, `ProviderConfigValidatorTest`.

### Phase 2 (Code Written, Tests in Progress):
1. **Redis Rate Limiter**: `RedisRateLimiter` using Lua script (`INCR` + `EXPIRE` window).
2. **Daily Token Budget**: `RedisUsageService` using UTC midnight reset, caps `maxOutputTokens`.
3. **HTTP 429**: `RateLimitException` returns which limit was hit with `Retry-After` header.
4. **Usage Counters**: Redis hash `usage:{apiKeyId}:{yyyy-MM-dd}` updated atomically with Lua script. TTL = 2 days.
5. **GET /usage**: `UsageController` returning requests, tokens, budget, remaining, reset time.
6. **Quota Headers**: `X-Tokens-Remaining` and `X-RateLimit-Remaining` added to `POST /chat`.
7. **Pending**: Add Phase 2 tests (`RedisRateLimiterTest`, `RedisUsageServiceTest`, `UsageControllerTest`).

## Common Commands
- Build & test: `./gradlew test` (or `gradlew.bat test` on Windows)
- Run local stack: `./run-local.sh` or `docker-compose up --build`
