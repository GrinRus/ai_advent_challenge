package com.aiadvent.backend.flow.api;

import java.time.Instant;
import java.util.UUID;

public record AgentDefinitionSummaryResponse(
    UUID id,
    String identifier,
    String displayName,
    String description,
    boolean active,
    String createdBy,
    String updatedBy,
    Instant createdAt,
    Instant updatedAt,
    Integer latestVersion,
    Integer latestPublishedVersion,
    Instant latestPublishedAt) {}
