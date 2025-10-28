package com.aiadvent.backend.mcp.agentops;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PreviewDependenciesResult(
    UUID id,
    String identifier,
    String displayName,
    boolean active,
    List<AgentVersionSnapshot> versions) {

  public record AgentVersionSnapshot(
      int version,
      String status,
      String providerId,
      String modelId,
      List<String> toolCodes,
      Instant createdAt,
      Instant publishedAt) {}
}

