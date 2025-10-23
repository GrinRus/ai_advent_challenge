package com.aiadvent.backend.flow.api;

import com.aiadvent.backend.chat.config.ChatProviderType;
import com.aiadvent.backend.flow.domain.AgentVersionStatus;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AgentVersionResponse(
    UUID id,
    int version,
    AgentVersionStatus status,
    ChatProviderType providerType,
    String providerId,
    String modelId,
    String systemPrompt,
    JsonNode defaultOptions,
    JsonNode toolBindings,
    JsonNode costProfile,
    boolean syncOnly,
    Integer maxTokens,
    String createdBy,
    String updatedBy,
    Instant createdAt,
    Instant publishedAt,
    List<AgentCapabilityResponse> capabilities) {}
