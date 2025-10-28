package com.aiadvent.backend.flow.blueprint;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

@JsonIgnoreProperties(ignoreUnknown = true)
public record FlowInteractionDraft(
    String type,
    String title,
    String description,
    JsonNode payloadSchema,
    JsonNode suggestedActions,
    Integer dueInMinutes) {}
