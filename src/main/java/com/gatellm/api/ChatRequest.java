package com.gatellm.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

public class ChatRequest {

    @JsonProperty("prompt")
    @NotBlank(message = "Prompt must not be blank")
    private String prompt;

    public ChatRequest() {}

    @JsonProperty("prompt")
    public String getPrompt() {
        return prompt;
    }

    @JsonProperty("prompt")
    public void setPrompt(String prompt) {
        this.prompt = prompt;
    }
}