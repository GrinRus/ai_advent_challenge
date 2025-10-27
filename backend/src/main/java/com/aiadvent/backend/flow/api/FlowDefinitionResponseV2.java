package com.aiadvent.backend.flow.api;

import com.aiadvent.backend.flow.blueprint.FlowBlueprint;
import com.aiadvent.backend.flow.domain.FlowDefinitionStatus;
import java.time.Instant;
import java.util.UUID;

public record FlowDefinitionResponseV2(
    UUID id,
    String name,
    int version,
    FlowDefinitionStatus status,
    boolean active,
    FlowBlueprint definition,
    String description,
    String updatedBy,
    Instant createdAt,
    Instant updatedAt,
    Instant publishedAt) {}
