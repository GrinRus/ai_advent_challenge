package com.aiadvent.backend.chat.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(
    description =
        "Payload for requesting a structured synchronous chat response. Mirrors the streaming request format and optionally carries overrides.",
    example =
        """
        {
          "message": "Summarize the discussion into key actions.",
          "provider": "zhipu",
          "model": "glm-4.6",
          "options": {
            "temperature": 0.1,
            "topP": 0.9,
            "maxTokens": 400
          }
        }
        """)
public record ChatSyncRequest(
    @Schema(description = "Existing chat session identifier.") UUID sessionId,
    @Schema(
            description = "User message to send to the provider.",
            example = "Summarize the discussion into key actions.",
            requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank
        String message,
    @Schema(description = "Explicit provider identifier; defaults to the configured provider.") String provider,
    @Schema(description = "Explicit model identifier; defaults to the provider's default model.") String model,
    @Schema(description = "Optional interaction mode: set to 'research' to enable Perplexity tooling.")
        String mode,
    @Schema(description = "Optional overrides for sampling parameters.") @Valid ChatStreamRequestOptions options) {}
