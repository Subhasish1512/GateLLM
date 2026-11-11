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
- `com.gatellm.security`: `ApiKeyFilter`, `ApiKeyAuthentication`, `AuthenticatedKeyResolver`, `SecurityConfig`, `PiiRedactor`
- `com.gatellm.ratelimit`: `RedisRateLimiter`, `RedisUsageService`, `RateLimitException`, `RateLimiterService`, `UsageService`
- `com.gatellm.llm`: `LlmProvider` interface, `AnthropicProvider`, `MockLlmProvider`, `LlmRouter` (ordered fallback)
- `com.gatellm.config`: `GateLlmProperties` (typed records), `ProviderConfigValidator`, `OpenApiConfig`
- `com.gatellm.metrics`: `GatewayMetrics` (Micrometer meters)

## Current Implementation State
### Phase 1 (Completed):
- Gradle setup (`build.gradle`, `settings.gradle`, `gradlew`, `gradlew.bat`).
- Core gateway: `POST /chat`, `LlmRouter`, `AnthropicProvider`, `MockLlmProvider`.
- API Key authentication filter using SHA-256 constant-time comparison.
- Multi-stage `Dockerfile`, `docker-compose.yml`, `run-local.sh`, `.env.example`, `.gitignore`.
- Tests for `ChatControllerTest`, `ApiKeyFilterTest`, `AnthropicProviderTest`, `MockLlmProviderTest`, `ProviderConfigValidatorTest`.

### Phase 2 (Completed):
1. **Redis Rate Limiter**: `RedisRateLimiter` using Lua script (`INCR` + `EXPIRE` window).
2. **Daily Token Budget**: `RedisUsageService` using UTC midnight reset, caps `maxOutputTokens`.
3. **HTTP 429**: `RateLimitException` returns which limit was hit with `Retry-After` header.
4. **Usage Counters**: Redis hash `usage:{apiKeyId}:{yyyy-MM-dd}` updated atomically with Lua script. TTL = 2 days.
5. **GET /usage**: `UsageController` returning requests, tokens, budget, remaining, reset time.
6. **Quota Headers**: `X-Tokens-Remaining` and `X-RateLimit-Remaining` added to `POST /chat`.

### Phase 3 (Completed):
1. **PII Redaction**: `PiiRedactor` with regex for emails (`[EMAIL]`), Indian phones (+91), international phones, US phones (`[PHONE]`).
2. **Enhanced Router**: `LlmRouter` accepts `apiKeyId`, tracks fallback count, records provider errors, only falls back on retryable errors.
3. **Provider-independent error types**: `LlmProviderException.ErrorType` (RATE_LIMITED, UNAVAILABLE, CLIENT_ERROR, UNKNOWN) with `isRetryable()`.
4. **AnthropicProvider**: Maps HTTP errors to ErrorType, uses RestClient with onStatus handlers.

### Phase 4 (Completed):
1. **Micrometer Metrics**: `GatewayMetrics` with counters (requests, rate limited, budget exceeded, fallback, provider errors by type), timer with p50/p95/p99 percentiles, token counters.
2. **Prometheus/Grafana Stack**: `docker-compose.yml` with prometheus (v2.53.0) and grafana (v10.4.0), `prometheus.yml`, provisioning configs.
3. **Grafana Dashboard**: 10 panels - latency percentiles, avg latency, token usage (5m rate), rate limit/budget exceeded (5m rate), fallback count (5m rate), provider errors by type (5m rate), 4 stat panels (request rate, 429 rate, fallback rate, budget exceeded rate).
4. **k6 Load Testing**: `loadtest/k6-script.js` with 3 scenarios (normal 50 VUs, burst ramp to 200 VUs, fallback 20 VUs with mock failures).
5. **OpenAPI Documentation**: `OpenApiConfig.java` with API key security scheme, Swagger UI at `/swagger-ui.html`.
6. **Demo Script**: `scripts/demo.sh` with 6 curl examples.
7. **README.md**: Complete documentation with architecture, security model, API examples, observability, load testing, design decisions.

## Common Commands
- Build & test: `./gradlew test` (or `gradlew.bat test` on Windows)
- Run local stack: `./run-local.sh` or `docker-compose up --build`
- Run with mock provider (no Anthropic key needed): `GATELLM_PROVIDER=mock docker-compose up --build`
- Access Swagger UI: http://localhost:8080/swagger-ui.html
- Access Grafana: http://localhost:3000 (admin/admin)
- Access Prometheus: http://localhost:9090
- Run k6 load test: `k6 run -e BASE_URL=http://localhost:8080 -e API_KEY=gk-test-key-1 loadtest/k6-script.js`

## Configuration (`.env`)
```bash
# Required for Anthropic provider
ANTHROPIC_API_KEY=sk-ant-...

# Optional overrides
GATELLM_PROVIDER=mock              # mock | anthropic (default: mock)
GATELLM_MAX_OUTPUT_TOKENS=1024
GATELLM_RATE_LIMIT_PER_MINUTE=10
GATELLM_DAILY_TOKEN_BUDGET=100000
GATELLM_MOCK_FAILURE_RATE=0.0      # 0.0-1.0 for fallback testing
```

## API Examples

### 1. Valid Chat Request
```bash
curl -X POST http://localhost:8080/chat \
  -H "Content-Type: application/json" \
  -H "X-API-Key: gk-test-key-1" \
  -d '{"prompt": "Explain quantum computing in one sentence."}'
```

### 2. Get Usage Statistics
```bash
curl -X GET http://localhost:8080/usage \
  -H "X-API-Key: gk-test-key-1"
```

### 3. PII Redaction Demo
```bash
curl -X POST http://localhost:8080/chat \
  -H "Content-Type: application/json" \
  -H "X-API-Key: gk-test-key-1" \
  -d '{"prompt": "Email me at john.doe@example.com or call +91-98765-43210"}'
```

## Key Implementation Details

### Rate Limiting (Redis + Lua)
- Key: `ratelimit:{apiKeyId}`
- Atomic INCR + EXPIRE in single Lua script
- Sliding window (60s default)
- Returns retry-after seconds in 429 response

### Daily Token Budget (Redis Hash)
- Key: `usage:{apiKeyId}:{YYYY-MM-DD}` (UTC midnight reset)
- Fields: `requests`, `inputTokens`, `outputTokens`
- Atomic HINCRBY via Lua script
- TTL = 2 days (survives midnight reset)
- Pre-check before request, hard cap on maxOutputTokens

### PII Redaction Patterns
- Emails: `[\w.-]+@[\w.-]+\.\w+` → `[EMAIL]`
- Indian phones: `+91[- ]?\d{5}[- ]?\d{5}`, `91\d{10}` → `[PHONE]`
- International: `\+\d{1,3}[- ]?\d{7,14}` → `[PHONE]`
- US: `\(\d{3}\)\s?\d{3}-\d{4}`, `\d{3}-\d{3}-\d{4}` → `[PHONE]`

### Provider Fallback Logic
1. Try targets in order (cheapest first: haiku → sonnet)
2. On error, classify: RATE_LIMITED, UNAVAILABLE, CLIENT_ERROR, UNKNOWN
3. Only fallback on retryable (RATE_LIMITED, UNAVAILABLE, UNKNOWN)
4. CLIENT_ERROR (4xx except 429) fails immediately
5. Track fallback count per model/apiKeyId
6. Record provider errors by type for alerting

### Metrics (Micrometer + Prometheus)
| Metric | Type | Labels | Description |
|--------|------|--------|-------------|
| `gateway_requests_total` | Counter | model, apiKeyId | Successful requests |
| `gateway_latency_seconds` | Timer | model, apiKeyId | Latency (p50/p95/p99) |
| `gateway_tokens_input_total` | Counter | model, apiKeyId | Input tokens |
| `gateway_tokens_output_total` | Counter | model, apiKeyId | Output tokens |
| `gateway_rate_limited_total` | Counter | model, apiKeyId | 429 responses |
| `gateway_budget_exceeded_total` | Counter | model, apiKeyId | Budget exceeded |
| `gateway_fallback_total` | Counter | model, apiKeyId | Fallback activations |
| `gateway_provider_errors_total` | Counter | model, apiKeyId, error_type | Errors by type |

### Security Model
- API Key format: `gk-{keyId}.{secret}` (configurable)
- Stored as SHA-256 hash in config
- Constant-time comparison using `MessageDigest.isEqual()`
- Never log raw keys - mask as `test****key1`
- Header: `X-API-Key`

## Running Locally (No Docker)
```bash
# 1. Start Redis
docker run -d -p 6379:6379 redis:7-alpine

# 2. Set env vars
export GATELLM_PROVIDER=mock
export GATELLM_MAX_OUTPUT_TOKENS=1024

# 3. Run
./gradlew bootRun
```

## Testing Fallback Locally
```bash
# Enable mock failures
export GATELLM_MOCK_FAILURE_RATE=0.5
./gradlew bootRun

# Send requests - ~50% will fallback to sonnet
curl -X POST http://localhost:8080/chat \
  -H "Content-Type: application/json" \
  -H "X-API-Key: gk-test-key-1" \
  -d '{"prompt": "Test fallback"}'
```

## Project Structure
```
GateLLM/
├── src/main/java/com/gatellm/
│   ├── api/                 # Controllers, DTOs, ExceptionHandler
│   ├── config/              # Properties, Validators, OpenAPI
│   ├── llm/                 # Provider interface + implementations
│   ├── metrics/             # GatewayMetrics (Micrometer)
│   ├── ratelimit/           # Redis rate limiter & usage service
│   └── security/            # Auth filter, PII redaction
├── src/main/resources/
│   ├── application.yml      # Spring config
│   └── .env.example         # Environment template
├── docker/
│   ├── Dockerfile           # Multi-stage build
│   └── docker-compose.yml   # Full stack (app, redis, prometheus, grafana)
├── grafana/
│   ├── provisioning/        # Datasources & dashboards as code
│   └── dashboards/          # GateLLM dashboard JSON
├── loadtest/
│   └── k6-script.js         # k6 scenarios
├── scripts/
│   └── demo.sh              # curl examples
├── build.gradle             # Gradle (Groovy DSL)
├── gradlew / gradlew.bat    # Wrapper
└── README.md                # Full documentation
```