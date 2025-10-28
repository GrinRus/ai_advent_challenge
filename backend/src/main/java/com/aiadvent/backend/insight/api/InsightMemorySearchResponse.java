package com.aiadvent.backend.insight.api;

import com.aiadvent.backend.insight.InsightSessionType;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record InsightMemorySearchResponse(
    InsightSessionType type, UUID sessionId, String query, List<MemoryMatch> matches) {

  public record MemoryMatch(
      String resourceType,
      String channel,
      String content,
      Instant createdAt,
      Integer sequenceNumber,
      Long version,
      String stepId,
      Integer stepAttempt,
      Long sourceStart,
      Long sourceEnd,
      UUID messageId,
      JsonNode payload) {}
}
