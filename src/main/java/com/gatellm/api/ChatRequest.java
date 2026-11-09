package com.gatellm.api;

import jakarta.validation.constraints.NotBlank;

public record ChatRequest(
        @NotBlank(message = "Prompt must not be blank") String prompt
) {}
