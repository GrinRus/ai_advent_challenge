package com.aiadvent.backend.flow.api;

import com.aiadvent.backend.flow.domain.FlowDefinitionStatus;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

public record FlowDefinitionResponse(
    UUID id,
    String name,
    int version,
    FlowDefinitionStatus status,
    boolean active,
    JsonNode definition,
    String description,
    String updatedBy,
    Instant createdAt,
    Instant updatedAt,
    Instant publishedAt) {}
