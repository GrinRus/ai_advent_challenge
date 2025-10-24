package com.aiadvent.backend.flow.api;

import com.aiadvent.backend.flow.domain.FlowInteractionResponseSource;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;

public record FlowInteractionRespondRequest(
    UUID chatSessionId,
    UUID respondedBy,
    FlowInteractionResponseSource source,
    JsonNode payload) {}
