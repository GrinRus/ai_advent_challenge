package com.aiadvent.backend.flow.api;

import com.aiadvent.backend.flow.domain.FlowInteractionStatus;
import com.aiadvent.backend.flow.domain.FlowInteractionType;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

public record FlowInteractionItemResponse(
    UUID requestId,
    String stepId,
    FlowInteractionStatus status,
    FlowInteractionType type,
    String title,
    String description,
    JsonNode payloadSchema,
    JsonNode suggestedActions,
    Instant createdAt,
    Instant updatedAt,
    Instant dueAt,
    FlowInteractionResponseSummary response) {}
