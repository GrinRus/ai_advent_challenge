package com.aiadvent.backend.mcp.agentops;

import java.util.UUID;

public record RegisterAgentResult(
    UUID id,
    String identifier,
    String displayName,
    boolean active,
    String createdAt,
    String updatedAt) {}

