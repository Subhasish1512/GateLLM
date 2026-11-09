/**
 * GateLLM k6 Load Test Script
 *
 * Runs against the mock provider (GATELLM_PROVIDER=mock).
 * Requires: k6 installed (https://k6.io/docs/getting-started/installation/)
 *
 * Usage:
 *   # Normal load test (50 VUs)
 *   k6 run -e BASE_URL=http://localhost:8080 -e API_KEY=gk-test-key-1 loadtest/k6-script.js
 *
 *   # Rate limit burst test
 *   k6 run -e BASE_URL=http://localhost:8080 -e API_KEY=gk-test-key-1 -e SCENARIO=burst loadtest/k6-script.js
 *
 *   # Fallback test (mock provider fails often)
 *   k6 run -e BASE_URL=http://localhost:8080 -e API_KEY=gk-test-key-1 -e SCENARIO=fallback loadtest/k6-script.js
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Trend, Counter } from 'k6/metrics';

// Custom metrics
const latencyTrend = new Trend('chat_latency_ms');
const rateLimitCounter = new Counter('rate_limit_429s');
const fallbackCounter = new Counter('fallbacks');
const errorCounter = new Counter('errors');

// Configuration from environment
const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const API_KEY = __ENV.API_KEY || 'gk-test-key-1';
const SCENARIO = __ENV.SCENARIO || 'normal'; // normal | burst | fallback

const headers = {
  'Content-Type': 'application/json',
  'X-API-Key': API_KEY,
};

// Test data
const prompts = [
  'Hello, how are you?',
  'Explain quantum computing in simple terms.',
  'Write a short poem about spring.',
  'What is the capital of France?',
  'Summarize the benefits of exercise.',
];

function randomPrompt() {
  return prompts[Math.floor(Math.random() * prompts.length)];
}

function chatRequest(prompt) {
  const payload = JSON.stringify({ prompt });
  const res = http.post(`${BASE_URL}/chat`, payload, { headers });

  // Record latency
  latencyTrend.add(res.timings.duration);

  // Check for rate limiting
  if (res.status === 429) {
    rateLimitCounter.add(1);
  }

  // Check for fallback (model different from first target)
  // First target is claude-haiku-5-5, second is claude-sonnet-5-5
  if (res.status === 200) {
    try {
      const body = JSON.parse(res.body);
      if (body.modelUsed && body.modelUsed.includes('sonnet')) {
        fallbackCounter.add(1);
      }
    } catch (e) {
      // ignore parse errors
    }
  }

  // Record errors
  if (res.status >= 400) {
    errorCounter.add(1);
  }

  return res;
}

// Scenarios configuration
export const options = {
  scenarios: {
    normal: {
      executor: 'constant-vus',
      vus: 50,
      duration: '60s',
      exec: 'normalLoad',
      tags: { scenario: 'normal' },
    },
    burst: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '10s', target: 200 }, // spike to 200 VUs
        { duration: '30s', target: 200 }, // sustain
        { duration: '10s', target: 0 },   // ramp down
      ],
      exec: 'burstLoad',
      tags: { scenario: 'burst' },
    },
    fallback: {
      executor: 'constant-vus',
      vus: 20,
      duration: '60s',
      exec: 'fallbackLoad',
      tags: { scenario: 'fallback' },
    },
  },
  thresholds: {
    'chat_latency_ms': ['p(95)<2000'], // p95 < 2s
    'http_req_failed': ['rate<0.1'],   // error rate < 10%
  },
};

// Normal load test - 50 VUs sending normal requests
export function normalLoad() {
  const prompt = randomPrompt();
  const res = chatRequest(prompt);

  check(res, {
    'status is 200': (r) => r.status === 200,
    'has answer': (r) => {
      try {
        return JSON.parse(r.body).answer !== undefined;
      } catch {
        return false;
      }
    },
    'has modelUsed': (r) => {
      try {
        return JSON.parse(r.body).modelUsed !== undefined;
      } catch {
        return false;
      }
    },
    'has tokens info': (r) => {
      try {
        const body = JSON.parse(r.body);
        return body.inputTokens !== undefined && body.outputTokens !== undefined;
      } catch {
        return false;
      }
    },
    'has latency header': (r) => r.headers['X-Tokens-Remaining'] !== undefined,
  });

  sleep(Math.random() * 2 + 0.5); // 0.5-2.5s think time
}

// Burst test - triggers rate limiter (10 req/min limit in config)
export function burstLoad() {
  const prompt = randomPrompt();
  const res = chatRequest(prompt);

  check(res, {
    'status is 200 or 429': (r) => r.status === 200 || r.status === 429,
  });

  // Minimal sleep to generate high request rate
  sleep(0.05);
}

// Fallback test - mock provider configured with high failure rate
export function fallbackLoad() {
  const prompt = randomPrompt();
  const res = chatRequest(prompt);

  check(res, {
    'status is 200 or 502': (r) => r.status === 200 || r.status === 502,
    'if 200, has fallback model': (r) => {
      if (r.status !== 200) return true;
      try {
        const body = JSON.parse(r.body);
        // Either model is fine, but we track fallbacks separately
        return body.modelUsed !== undefined;
      } catch {
        return false;
      }
    },
  });

  sleep(Math.random() * 1 + 0.2); // 0.2-1.2s think time
}

// Summary output
export function handleSummary(data) {
  const summary = {
    'stdout': textSummary(data, { indent: ' ', enableColors: true }),
    'summary.json': JSON.stringify(data, null, 2),
  };
  return summary;
}

function textSummary(data, options = {}) {
  const indent = options.indent || '';
  const enableColors = options.enableColors || false;
  const color = (c) => enableColors ? c : '';
  const reset = enableColors ? '\x1b[0m' : '';
  const green = color('\x1b[32m');
  const red = color('\x1b[31m');
  const yellow = color('\x1b[33m');
  const cyan = color('\x1b[36m');

  let out = '\n';
  out += `${cyan}═══ GateLLM Load Test Summary ═══${reset}\n\n`;

  // HTTP metrics
  const httpMetrics = data.metrics.http_req_duration || {};
  if (httpMetrics.values) {
    out += `${indent}HTTP Request Duration:\n`;
    out += `${indent}  p50: ${httpMetrics.values['p(50)']?.toFixed(2) || 'N/A'}ms\n`;
    out += `${indent}  p95: ${yellow}${httpMetrics.values['p(95)']?.toFixed(2) || 'N/A'}ms${reset}\n`;
    out += `${indent}  p99: ${httpMetrics.values['p(99)']?.toFixed(2) || 'N/A'}ms\n`;
  }

  // Custom metrics
  if (data.metrics.chat_latency_ms?.values) {
    out += `\n${indent}Chat Latency (custom):\n`;
    out += `${indent}  p50: ${data.metrics.chat_latency_ms.values['p(50)']?.toFixed(2) || 'N/A'}ms\n`;
    out += `${indent}  p95: ${yellow}${data.metrics.chat_latency_ms.values['p(95)']?.toFixed(2) || 'N/A'}ms${reset}\n`;
    out += `${indent}  p99: ${data.metrics.chat_latency_ms.values['p(99)']?.toFixed(2) || 'N/A'}ms\n`;
  }

  // Counters
  const rateLimitCount = data.metrics.rate_limit_429s?.values?.count || 0;
  const fallbackCount = data.metrics.fallbacks?.values?.count || 0;
  const errorCount = data.metrics.errors?.values?.count || 0;

  out += `\n${indent}Counters:\n`;
  out += `${indent}  Rate Limited (429): ${rateLimitCount > 0 ? red : green}${rateLimitCount}${reset}\n`;
  out += `${indent}  Fallbacks: ${fallbackCount > 0 ? yellow : green}${fallbackCount}${reset}\n`;
  out += `${indent}  Errors: ${errorCount > 0 ? red : green}${errorCount}${reset}\n`;

  // Checks
  out += `\n${indent}Checks:\n`;
  for (const [name, check] of Object.entries(data.checks || {})) {
    const passRate = (check.passes / (check.passes + check.fails) * 100).toFixed(1);
    const color = passRate === '100.0' ? green : (passRate > 90 ? yellow : red);
    out += `${indent}  ${name}: ${color}${passRate}%${reset} (${check.passes}/${check.passes + check.fails})\n`;
  }

  out += `\n${cyan}══════════════════════════════════════${reset}\n`;
  out += `${indent}Note: This test runs against the mock provider (GATELLM_PROVIDER=mock).\n`;
  out += `${indent}For fallback test, set mock.failure-rate > 0 in application.yml.\n\n`;

  return out;
}