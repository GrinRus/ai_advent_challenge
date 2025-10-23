package com.aiadvent.backend.flow.api;

import com.aiadvent.backend.flow.domain.FlowDefinitionStatus;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

public record FlowDefinitionHistoryResponse(
    long id,
    int version,
    FlowDefinitionStatus status,
    JsonNode definition,
    String changeNotes,
    String createdBy,
    Instant createdAt) {}
