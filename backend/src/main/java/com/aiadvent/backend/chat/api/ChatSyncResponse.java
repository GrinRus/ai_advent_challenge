package com.aiadvent.backend.chat.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Plain synchronous chat response returned by the provider.")
public record ChatSyncResponse(
    @Schema(
            description = "Identifier for correlating the request/response pair.",
            example = "9b2d1d60-12f1-4f9d-9c70-4ce2c2b6817b")
        UUID requestId,
    @Schema(
            description = "Textual completion returned by the provider.",
            example = "Here is the summary of the requested topic.")
        String content,
    @Schema(description = "Technical metadata about the provider and model used.")
        StructuredSyncProvider provider,
    @Schema(description = "Token usage reported by the provider.") StructuredSyncUsageStats usage,
    @Schema(description = "Cost breakdown calculated by the backend based on usage and pricing.")
        UsageCostDetails cost,
    @Schema(description = "Total latency in milliseconds observed by the backend.", example = "640")
        Long latencyMs,
    @Schema(description = "UTC timestamp when the response was generated.")
        Instant timestamp) {}
