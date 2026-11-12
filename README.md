# GateLLM Gateway

> **A Spring Boot gateway for LLM providers** — handles cost control, abuse prevention, and reliability for Anthropic Claude (and extensible to others).

---

## Why This Exists

LLM APIs are expensive, unreliable, and prone to abuse. GateLLM sits between your application and the LLM provider to:

- **Control costs** — Daily token budgets per API key with hard caps
- **Prevent abuse** — Per-key rate limiting (sliding window, Redis-backed)
- **Ensure reliability** — Automatic fallback from cheap to premium models on 429/5xx/timeouts
- **Protect privacy** — Automatic PII redaction (emails, phone numbers) before prompts leave your infrastructure
- **Observe everything** — Prometheus metrics + Grafana dashboards out of the box

No caching, no embeddings, no unnecessary complexity — just a focused gateway that does one thing well.

---

## Architecture

```
┌─────────────┐     ┌──────────────┐     ┌──────────────────┐
│   Client    │────▶│  GateLLM     │────▶│  LLM Providers   │
│  (your app) │     │  Gateway     │     │  (Anthropic,     │
└─────────────┘     └──────┬───────┘     │   Mock, etc.)    │
                           │             └──────────────────┘
                    ┌──────▼──────┐
                    │   Redis     │
                    │ (rate limit,│
                    │  usage,     │
                    │  budgets)   │
                    └─────────────┘
```

### Request Flow

```
POST /chat
    │
    ├─▶ Authenticate (X-API-Key header, SHA-256 constant-time compare)
    ├─▶ Rate Limit Check (Redis INCR + EXPIRE, Lua script, 10 req/min default)
    ├─▶ Budget Check (Redis Hash HINCRBY, UTC midnight reset)
    ├─▶ PII Redaction (emails → [EMAIL], phones → [PHONE])
    ├─▶ Route to LLM (cheap model first, fallback on retryable errors)
    │       ├─▶ claude-haiku-5-5 (primary)
    │       └─▶ claude-sonnet-5-5 (fallback)
    ├─▶ Record Usage (atomic Redis Hash update)
    └─▶ Return Response + Quota Headers
```

### Core Components

| Package | Responsibility |
|---------|----------------|
| `api` | Controllers, DTOs, GlobalExceptionHandler |
| `security` | ApiKeyFilter, PiiRedactor, AuthenticatedKeyResolver |
| `ratelimit` | RedisRateLimiter, RedisUsageService, RateLimitException |
| `llm` | LlmProvider interface, AnthropicProvider, MockLlmProvider, LlmRouter |
| `config` | GateLlmProperties, ProviderConfigValidator, OpenApiConfig |
| `metrics` | GatewayMetrics (Micrometer counters, timers, histograms) |

---

## Security Model

### Authentication
- **Header**: `X-API-Key: gk-<key-id>.<secret>`
- **Verification**: SHA-256 hash stored in config, constant-time comparison (prevents timing attacks)
- **No logging** of raw keys — only masked (`gk-test****key1`)

### Rate Limiting
- **Algorithm**: Sliding window (Lua script: `INCR` + `EXPIRE`)
- **Default**: 10 requests/minute per key
- **Response**: `429 Too Many Requests` with `Retry-After` header

### Token Budget
- **Scope**: Daily, per API key, resets at UTC midnight
- **Storage**: Redis Hash `usage:{apiKeyId}:{yyyy-MM-dd}` with 2-day TTL
- **Enforcement**: Pre-check before request, hard cap on `maxOutputTokens`
- **Response**: `429 Too Many Requests` with `Retry-After` = seconds until midnight UTC

### PII Redaction
| Pattern | Replacement |
|---------|-------------|
| Email addresses | `[EMAIL]` |
| Indian phones (+91-XXXXX-XXXXX, +91 XXXXX XXXXX, 91XXXXXXXXXX) | `[PHONE]` |
| International phones (+CC XXXXXXXXXXX) | `[PHONE]` |
| US phones ((XXX) XXX-XXXX, XXX-XXX-XXXX, etc.) | `[PHONE]` |

---

## How to Run

### Prerequisites
- Java 21
- Docker & Docker Compose
- (Optional) k6 for load testing

### Quick Start (Docker Compose)

```bash
# 1. Copy env template
cp .env.example .env
# Edit .env with your Anthropic API key (optional — mock provider works without)

# 2. Start full stack (app + Redis + Prometheus + Grafana)
docker-compose up --build

# 3. Verify
curl http://localhost:8080/actuator/health
# {"status":"UP"}
```

### Local Development (no Docker)

```bash
# 1. Start Redis
docker run -d -p 6379:6379 redis:7-alpine

# 2. Configure (copy .env.example to .env and edit)
#    Set GATELLM_PROVIDER=mock for no external dependencies

# 3. Run
./gradlew bootRun
```

### Configuration (`.env`)

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

---

## API Examples

### 1. Valid Chat Request

```bash
curl -X POST http://localhost:8080/chat \
  -H "Content-Type: application/json" \
  -H "X-API-Key: gk-test-key-1" \
  -d '{"prompt": "Explain quantum computing in one sentence."}'
```

**Response (200):**
```json
{
  "answer": "Quantum computing uses quantum bits that can exist in superposition...",
  "modelUsed": "claude-haiku-5-5",
  "inputTokens": 12,
  "outputTokens": 45,
  "latencyMs": 234
}
```

**Headers:**
```
X-Tokens-Remaining: 99943
X-RateLimit-Remaining: 9
```

---

### 2. Invalid API Key (401)

```bash
curl -X POST http://localhost:8080/chat \
  -H "Content-Type: application/json" \
  -H "X-API-Key: invalid-key" \
  -d '{"prompt": "Hello"}'
```

**Response (401):**
```json
{
  "timestamp": "2025-01-15T10:30:00Z",
  "status": 401,
  "error": "Unauthorized",
  "message": "Invalid API key",
  "path": "/chat"
}
```

---

### 3. Rate Limited (429)

```bash
# Send 15 rapid requests (limit is 10/min)
for i in {1..15}; do
  curl -s -w " %{http_code}\n" -X POST http://localhost:8080/chat \
    -H "Content-Type: application/json" \
    -H "X-API-Key: gk-test-key-1" \
    -d "{\"prompt\": \"Request $i\"}"
done
```

**Response (429):**
```json
{
  "timestamp": "2025-01-15T10:30:05Z",
  "status": 429,
  "error": "Too Many Requests",
  "message": "Rate limit exceeded. Retry after 45 seconds.",
  "path": "/chat"
}
```
**Headers:**
```
Retry-After: 45
```

---

### 4. Get Usage Statistics

```bash
curl -X GET http://localhost:8080/usage \
  -H "X-API-Key: gk-test-key-1"
```

**Response (200):**
```json
{
  "apiKeyId": "test-key-1",
  "date": "2025-01-15",
  "requests": 7,
  "inputTokens": 84,
  "outputTokens": 315,
  "dailyTokenBudget": 100000,
  "tokensRemaining": 99601,
  "rateLimitPerMinute": 10,
  "rateLimitRemaining": 3,
  "budgetResetsAt": "2025-01-16T00:00:00Z"
}
```

---

### 5. Trigger Fallback (Mock Failure)

```bash
# 1. Enable mock failures in .env:
#    GATELLM_MOCK_FAILURE_RATE=0.5
#    docker-compose restart app

# 2. Send request — ~50% will fallback to sonnet
curl -X POST http://localhost:8080/chat \
  -H "Content-Type: application/json" \
  -H "X-API-Key: gk-test-key-1" \
  -d '{"prompt": "Test fallback"}'
```

**Response (200, from fallback):**
```json
{
  "answer": "Mock response from claude-sonnet-5-5 (fallback)",
  "modelUsed": "claude-sonnet-5-5",
  "inputTokens": 10,
  "outputTokens": 25,
  "latencyMs": 156
}
```

---

### 6. PII Redaction Demo

```bash
curl -X POST http://localhost:8080/chat \
  -H "Content-Type: application/json" \
  -H "X-API-Key: gk-test-key-1" \
  -d '{"prompt": "Email me at john.doe@example.com or call +91-98765-43210"}'
```

**The LLM receives:** `Email me at [EMAIL] or call [PHONE]`

---

## Usage Tracking

### Sample GET /usage Response

```json
{
  "apiKeyId": "prod-key-abc123",
  "date": "2025-01-15",
  "requests": 142,
  "inputTokens": 15234,
  "outputTokens": 48921,
  "dailyTokenBudget": 100000,
  "tokensRemaining": 35845,
  "rateLimitPerMinute": 10,
  "rateLimitRemaining": 8,
  "budgetResetsAt": "2025-01-16T00:00:00Z"
}
```

### Redis Keys (for debugging)

```bash
# Rate limit counter (sliding window, TTL = 60s)
redis-cli GET "ratelimit:test-key-1:1705315200"

# Daily usage hash (TTL = 2 days)
redis-cli HGETALL "usage:test-key-1:2025-01-15"
# 1) "requests"      "142"
# 2) "inputTokens"   "15234"
# 3) "outputTokens"  "48921"
```

---

## Observability

### Prometheus Metrics
Available at `GET /actuator/prometheus`

| Metric | Type | Labels | Description |
|--------|------|--------|-------------|
| `gateway_requests_total` | Counter | model, apiKeyId | Total successful requests |
| `gateway_latency_seconds` | Timer/Histogram | model, apiKeyId | Request latency (p50, p95, p99) |
| `gateway_tokens_input_total` | Counter | model, apiKeyId | Input tokens consumed |
| `gateway_tokens_output_total` | Counter | model, apiKeyId | Output tokens generated |
| `gateway_rate_limited_total` | Counter | model, apiKeyId | 429 responses |
| `gateway_budget_exceeded_total` | Counter | model, apiKeyId | Budget exceeded rejections |
| `gateway_fallback_total` | Counter | model, apiKeyId | Fallback activations |
| `gateway_provider_errors_total` | Counter | model, apiKeyId, error_type | Provider errors by type |

### Grafana Dashboard
- **URL**: http://localhost:3000 (admin / admin)
- **Datasource**: Prometheus (auto-provisioned)
- **Dashboard**: "GateLLM Gateway Dashboard" (auto-provisioned)

**Panels:**
1. Latency Percentiles (p50, p95, p99) by model
2. Average Latency by model
3. Token Usage (5m rate) — input vs output
4. Rate Limit & Budget Exceeded (5m rate)
5. Fallback Count (5m rate)
6. Provider Errors by Type (5m rate)
7. Stat: Total Request Rate
8. Stat: 429 Rejection Rate
9. Stat: Fallback Rate
10. Stat: Budget Exceeded Rate

---

## Load Testing (k6)

```bash
# Install k6: https://k6.io/docs/getting-started/installation/

# Normal load (50 VUs, 60s)
k6 run -e BASE_URL=http://localhost:8080 -e API_KEY=gk-test-key-1 loadtest/k6-script.js

# Burst test (spike to 200 VUs — triggers rate limiter)
k6 run -e BASE_URL=http://localhost:8080 -e API_KEY=gk-test-key-1 -e SCENARIO=burst loadtest/k6-script.js

# Fallback test (requires GATELLM_MOCK_FAILURE_RATE > 0)
k6 run -e BASE_URL=http://localhost:8080 -e API_KEY=gk-test-key-1 -e SCENARIO=fallback loadtest/k6-script.js
```

### Expected Output
```
═══ GateLLM Load Test Summary ═══

HTTP Request Duration:
  p50: 45.23ms
  p95: 187.45ms
  p99: 423.12ms

Chat Latency (custom):
  p50: 43.12ms
  p95: 182.34ms
  p99: 418.56ms

Counters:
  Rate Limited (429): 247
  Fallbacks: 12
  Errors: 0

Checks:
  status is 200: 95.3% (1143/1200)
  has answer: 100.0% (1143/1143)
  has modelUsed: 100.0% (1143/1143)
```

---

## Adding a New LLM Provider

To gate a new model/provider, you need:

### 1. New Provider Class
Create `src/main/java/com/gatellm/llm/<Provider>Provider.java` implementing `LlmProvider`:
- `name()` — unique id (e.g., `"openrouter"`)
- `call(modelId, prompt, maxOutputTokens)` → `LlmResponse`
- Map provider request/response format
- Map HTTP errors → `LlmProviderException.ErrorType` (RATE_LIMITED, UNAVAILABLE, CLIENT_ERROR, UNKNOWN)

### 2. Configuration (`GateLlmProperties.java`)
Add fields to the record:
```java
@DefaultValue("") String <provider>ApiKey;
@DefaultValue("https://api.provider.com") String <provider>BaseUrl;
@DefaultValue("default-model") String <provider>Model;
```

### 3. Router Registration (`LlmRouter.java`)
Add provider bean to constructor (order = cost priority, cheapest first)

### 4. Targets + Config (`application.yml`)
```yaml
gatellm:
  targets:
    - provider: <provider-name>
      model: <model-id>
  <provider>-api-key: ${<PROVIDER>_API_KEY:}
  <provider>-base-url: https://api.provider.com
  <provider>-model: <default-model>
```

### 5. Environment (`.env.example`)
```bash
<PROVIDER>_API_KEY=your-key-here
```

---

## Results Table

| Metric | Target | Actual | Notes |
|--------|--------|--------|-------|
| p95 latency (mock) | < 200ms | — | Run `k6` to populate |
| p99 latency (mock) | < 500ms | — | |
| Rate limit accuracy | Exact | — | Redis atomic INCR |
| Budget enforcement | Hard cap | — | Pre-check + capped output |
| Fallback success rate | > 99% | — | On retryable errors only |
| PII redaction coverage | Email + phone | — | Regex-based |
| Test coverage | 80%+ | — | `./gradlew jacocoTestReport` |

---

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
└── README.md                # This file
```

---

## License

Apache 2.0 — see [LICENSE](LICENSE) for details.