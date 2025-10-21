package com.aiadvent.backend.chat.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Aggregated token usage and cost information for a chat session.")
public record SessionUsageResponse(
    @Schema(description = "Identifier of the chat session.") UUID sessionId,
    @Schema(description = "Per-message breakdown of usage and cost.")
        List<MessageUsageBreakdown> messages,
    @Schema(description = "Total usage and cost aggregated across the session.") UsageTotals totals) {

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record MessageUsageBreakdown(
      UUID messageId,
      Integer sequenceNumber,
      String role,
      String provider,
      String model,
      StructuredSyncUsageStats usage,
      UsageCostDetails cost,
      Instant createdAt) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record UsageTotals(StructuredSyncUsageStats usage, UsageCostDetails cost) {}
}
