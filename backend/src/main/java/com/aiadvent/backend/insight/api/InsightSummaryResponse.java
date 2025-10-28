package com.aiadvent.backend.insight.api;

import com.aiadvent.backend.insight.InsightSessionType;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record InsightSummaryResponse(
    InsightSessionType type, UUID sessionId, List<Entry> entries) {

  public record Entry(
      String channel,
      Long sourceStart,
      Long sourceEnd,
      String summary,
      Long tokenCount,
      String language,
      JsonNode metadata,
      Instant createdAt) {}
}
