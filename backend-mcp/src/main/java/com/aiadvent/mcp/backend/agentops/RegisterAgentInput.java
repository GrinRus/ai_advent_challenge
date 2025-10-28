package com.aiadvent.mcp.backend.agentops;

public record RegisterAgentInput(
    String identifier,
    String displayName,
    String description,
    Boolean active,
    String createdBy,
    String updatedBy) {}

