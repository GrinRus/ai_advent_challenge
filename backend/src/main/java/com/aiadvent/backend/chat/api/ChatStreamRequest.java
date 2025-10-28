package com.aiadvent.backend.chat.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(
    description = "Payload for requesting a streaming chat completion.",
    example =
        """
        {
          "message": "Explain the key risks of the release plan.",
          "provider": "openai",
          "model": "gpt-4o",
          "options": {
            "temperature": 0.25,
            "topP": 0.85,
            "maxTokens": 600
          }
        }
        """)
public record ChatStreamRequest(
    @Schema(description = "Existing chat session identifier.") UUID sessionId,
    @Schema(
            description = "User message to send to the provider.",
            example = "Explain the key risks of the release plan.",
            requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank
        String message,
    @Schema(description = "Explicit provider identifier; defaults to the configured provider.") String provider,
    @Schema(description = "Explicit model identifier; defaults to the provider's default model.") String model,
    @Schema(description = "Optional interaction mode: set to 'research' to enable MCP tooling.")
        String mode,
    @Schema(description = "Explicit tool codes to request from MCP servers (case-insensitive).")
        List<String> requestedToolCodes,
    @Schema(description = "Optional overrides for sampling parameters.") @Valid ChatStreamRequestOptions options) {

  public ChatStreamRequest(
      UUID sessionId,
      String message,
      String provider,
      String model,
      String mode,
      @Valid ChatStreamRequestOptions options) {
    this(sessionId, message, provider, model, mode, null, options);
  }
}
