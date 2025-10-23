package com.aiadvent.backend.flow.api;

import com.aiadvent.backend.chat.provider.model.ChatRequestOverrides;
import com.aiadvent.backend.flow.domain.FlowSessionStatus;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

public record FlowStartResponse(
    UUID sessionId,
    FlowSessionStatus status,
    Instant startedAt,
    JsonNode launchParameters,
    JsonNode sharedContext,
    ChatRequestOverrides overrides) {}
