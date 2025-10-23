package com.aiadvent.backend.flow.api;

import com.aiadvent.backend.flow.domain.FlowDefinitionStatus;
import java.time.Instant;
import java.util.UUID;

public record FlowDefinitionSummaryResponse(
    UUID id,
    String name,
    int version,
    FlowDefinitionStatus status,
    boolean active,
    String description,
    String updatedBy,
    Instant updatedAt,
    Instant publishedAt) {}
