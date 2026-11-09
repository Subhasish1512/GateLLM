#!/usr/bin/env bash
set -euo pipefail

# GateLLM Demo Script
# Runs a series of curl examples against a running GateLLM instance
# Defaults to mock provider on localhost:8080

BASE_URL="${BASE_URL:-http://localhost:8080}"
API_KEY="${API_KEY:-gk-test-key-1}"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

print_step() {
    echo -e "\n${BLUE}━━━ $1 ━━━${NC}"
}

print_json() {
    if command -v jq &> /dev/null; then
        echo "$1" | jq .
    else
        echo "$1"
    fi
}

print_step "1. Valid /chat call"
echo -e "${YELLOW}Request:${NC} POST $BASE_URL/chat with valid API key"
RESPONSE=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/chat" \
    -H "Content-Type: application/json" \
    -H "X-API-Key: $API_KEY" \
    -d '{"prompt": "Hello, how are you?"}')
HTTP_CODE=$(echo "$RESPONSE" | tail -n1)
BODY=$(echo "$RESPONSE" | head -n -1)
echo -e "${YELLOW}Response ($HTTP_CODE):${NC}"
print_json "$BODY"

print_step "2. Invalid API key (401 Unauthorized)"
echo -e "${YELLOW}Request:${NC} POST $BASE_URL/chat with invalid API key"
RESPONSE=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/chat" \
    -H "Content-Type: application/json" \
    -H "X-API-Key: invalid-key-12345" \
    -d '{"prompt": "This should fail"}')
HTTP_CODE=$(echo "$RESPONSE" | tail -n1)
BODY=$(echo "$RESPONSE" | head -n -1)
echo -e "${YELLOW}Response ($HTTP_CODE):${NC}"
print_json "$BODY"

print_step "3. Rate-limited burst (429 Too Many Requests)"
echo -e "${YELLOW}Request:${NC} Sending 15 rapid requests (limit is 10/min)..."
for i in {1..15}; do
    RESPONSE=$(curl -s -w "%{http_code}" -X POST "$BASE_URL/chat" \
        -H "Content-Type: application/json" \
        -H "X-API-Key: $API_KEY" \
        -d "{\"prompt\": \"Burst request $i\"}")
    HTTP_CODE="${RESPONSE: -3}"
    if [[ "$HTTP_CODE" == "429" ]]; then
        echo -e "  Request $i: ${RED}429 Too Many Requests${NC} (rate limited)"
    elif [[ "$HTTP_CODE" == "200" ]]; then
        echo -e "  Request $i: ${GREEN}200 OK${NC}"
    else
        echo -e "  Request $i: ${YELLOW}$HTTP_CODE${NC}"
    fi
done
echo -e "\n${YELLOW}Note:${NC} After 10 requests, subsequent ones should return 429 with Retry-After header"

print_step "4. GET /usage (current day usage)"
echo -e "${YELLOW}Request:${NC} GET $BASE_URL/usage"
RESPONSE=$(curl -s -w "\n%{http_code}" -X GET "$BASE_URL/usage" \
    -H "X-API-Key: $API_KEY")
HTTP_CODE=$(echo "$RESPONSE" | tail -n1)
BODY=$(echo "$RESPONSE" | head -n -1)
echo -e "${YELLOW}Response ($HTTP_CODE):${NC}"
print_json "$BODY"

print_step "5. Request that triggers fallback (mock provider failure)"
echo -e "${YELLOW}Note:${NC} This requires mock provider with failure-rate > 0 in config."
echo -e "  To test: set ${GREEN}gatellm.mock.failure-rate=0.5${NC} in application.yml and restart."
echo -e "  Then run this request - it should fall back to the second target (claude-sonnet-5-5)."
echo ""
echo -e "${YELLOW}Request:${NC} POST $BASE_URL/chat"
RESPONSE=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/chat" \
    -H "Content-Type: application/json" \
    -H "X-API-Key: $API_KEY" \
    -d '{"prompt": "Test fallback"}')
HTTP_CODE=$(echo "$RESPONSE" | tail -n1)
BODY=$(echo "$RESPONSE" | head -n -1)
echo -e "${YELLOW}Response ($HTTP_CODE):${NC}"
print_json "$BODY"
if echo "$BODY" | grep -q "sonnet"; then
    echo -e "${GREEN}✓ Fallback triggered - response from claude-sonnet-5-5${NC}"
fi

print_step "6. PII Redaction Demo"
echo -e "${YELLOW}Request:${NC} POST $BASE_URL/chat with email and phone in prompt"
RESPONSE=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/chat" \
    -H "Content-Type: application/json" \
    -H "X-API-Key: $API_KEY" \
    -d '{"prompt": "Contact me at john.doe@example.com or call +91-98765-43210 for details."}')
HTTP_CODE=$(echo "$RESPONSE" | tail -n1)
BODY=$(echo "$RESPONSE" | head -n -1)
echo -e "${YELLOW}Response ($HTTP_CODE):${NC}"
print_json "$BODY"
echo -e "${GREEN}Note:${NC} The prompt sent to the LLM had emails/phones replaced with [EMAIL]/[PHONE]"

print_step "Demo Complete"
echo -e "${BLUE}Additional endpoints:${NC}"
echo "  - Prometheus metrics: $BASE_URL/actuator/prometheus"
echo "  - Health check: $BASE_URL/actuator/health"
echo "  - Swagger UI: $BASE_URL/swagger-ui.html"
echo "  - Grafana: http://localhost:3000 (admin/admin)"
echo "  - Prometheus: http://localhost:9090"