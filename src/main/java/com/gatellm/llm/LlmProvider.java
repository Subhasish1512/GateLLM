package com.gatellm.llm;

/**
 * Abstraction over any LLM provider (Anthropic, OpenAI, mock, etc.).
 * Adding a new provider requires only a new implementation and a config entry.
 */
public interface LlmProvider {

    /** Unique name matching the {@code provider} field in target config. */
    String name();

    /** Send a prompt to the given model and return the response with token usage. */
    LlmResponse call(String modelId, String prompt, int maxOutputTokens);
}
