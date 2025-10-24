package com.aiadvent.backend.flow.config;

import com.aiadvent.backend.flow.domain.FlowInteractionType;
import com.fasterxml.jackson.databind.JsonNode;

public record FlowInteractionConfig(
    FlowInteractionType type,
    String title,
    String description,
    JsonNode payloadSchema,
    JsonNode suggestedActions,
    Integer dueInMinutes) {

  public FlowInteractionConfig {
    type = type != null ? type : FlowInteractionType.INPUT_FORM;
    suggestedActions = suggestedActions != null ? suggestedActions : null;
    dueInMinutes = dueInMinutes != null && dueInMinutes > 0 ? dueInMinutes : null;
  }
}
