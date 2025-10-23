package com.aiadvent.backend.flow.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AgentDefinitionResponse(
    UUID id,
    String identifier,
    String displayName,
    String description,
    boolean active,
    String createdBy,
    String updatedBy,
    Instant createdAt,
    Instant updatedAt,
    List<AgentVersionResponse> versions) {}
