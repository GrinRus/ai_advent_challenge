package com.aiadvent.backend.chat.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
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
    @Schema(description = "Optional interaction mode: set to 'research' to enable MCP tooling.")
        String mode,
    @Schema(description = "Explicit tool codes to request from MCP servers (case-insensitive).")
        List<String> requestedToolCodes,
    @Schema(
            description =
                "Optional per-tool overrides that will be merged into the MCP payloads before invocation.")
        Map<String, JsonNode> requestOverridesByTool,
    @Schema(description = "Optional overrides for sampling parameters.") @Valid ChatStreamRequestOptions options) {

  public ChatSyncRequest(
      UUID sessionId,
      String message,
      String provider,
      String model,
      String mode,
      @Valid ChatStreamRequestOptions options) {
    this(sessionId, message, provider, model, mode, null, null, options);
  }
}
