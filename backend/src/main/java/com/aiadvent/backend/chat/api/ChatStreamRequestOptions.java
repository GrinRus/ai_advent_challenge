package com.aiadvent.backend.chat.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Optional overrides for provider sampling parameters.")
public record ChatStreamRequestOptions(
    @Schema(
            description = "Temperature override applied to the model.",
            example = "0.2",
            minimum = "0",
            maximum = "2")
        Double temperature,
    @Schema(
            description = "Top-P nucleus sampling value.",
            example = "0.9",
            minimum = "0",
            maximum = "1")
        Double topP,
    @Schema(
            description = "Maximum number of tokens to generate.",
            example = "512",
            minimum = "1")
        Integer maxTokens) {}
