package com.aiadvent.backend.mcp.agentops;

import java.time.Instant;
import java.util.UUID;

public record AgentSummary(
    UUID id,
    String identifier,
    String displayName,
    boolean active,
    Integer latestVersion,
    Integer latestPublishedVersion,
    Instant updatedAt,
    Instant createdAt) {}

