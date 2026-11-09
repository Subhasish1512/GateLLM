#!/usr/bin/env bash
set -euo pipefail

echo "=== GateLLM Local Startup ==="
echo ""

# Create .env from example if missing
if [ ! -f .env ]; then
    cp .env.example .env
    echo "Created .env from .env.example (mock provider by default)"
fi

# Generate the dev key hash and inject it into .env
DEV_KEY="gk-test-key-1"
DEV_KEY_HASH=$(echo -n "$DEV_KEY" | sha256sum | cut -d' ' -f1)

if ! grep -q "GATELLM_DEV_KEY_HASH" .env; then
    echo "GATELLM_DEV_KEY_HASH=${DEV_KEY_HASH}" >> .env
fi

echo "Dev API key : $DEV_KEY"
echo "Dev key hash: $DEV_KEY_HASH"
echo "Provider    : ${GATELLM_PROVIDER:-mock}"
echo ""

# Ensure the Gradle wrapper is present
if [ ! -f gradlew ]; then
    echo "ERROR: Gradle wrapper not found."
    echo "Run:  gradle wrapper --gradle-version 8.12"
    exit 1
fi

chmod +x gradlew

echo "Building and starting GateLLM..."
docker-compose up --build
