package com.aiadvent.backend.flow.api;

import com.aiadvent.backend.flow.domain.FlowInteractionResponseSource;
import com.aiadvent.backend.flow.domain.FlowInteractionStatus;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

public record FlowInteractionResponseSummary(
    UUID responseId,
    FlowInteractionResponseSource source,
    UUID respondedBy,
    Instant respondedAt,
    FlowInteractionStatus status,
    JsonNode payload) {}
