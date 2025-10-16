package com.aiadvent.backend.chat.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Structured synchronous chat response returned by the provider.")
public record StructuredSyncResponse(
    @Schema(
            description = "Identifier for correlating the request/response pair.",
            example = "4bd0f474-78e8-4ffd-a990-3aa54f0704c3")
        UUID requestId,
    @Schema(description = "Processing status reported by the provider.", example = "success")
        StructuredSyncStatus status,
    @Schema(description = "Technical metadata about the provider and model used.")
        StructuredSyncProvider provider,
    @Schema(description = "Domain-specific structured answer produced by the provider.")
        StructuredSyncAnswer answer,
    @Schema(description = "Token usage reported by the provider.") StructuredSyncUsageStats usage,
    @Schema(description = "Total latency in milliseconds observed by the backend.", example = "1240")
        Long latencyMs,
    @Schema(description = "UTC timestamp when the response was generated.")
        Instant timestamp) {}
