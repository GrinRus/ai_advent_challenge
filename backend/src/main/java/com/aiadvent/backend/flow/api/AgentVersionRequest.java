package com.aiadvent.backend.flow.api;

import com.aiadvent.backend.chat.config.ChatProviderType;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

public record AgentVersionRequest(
    ChatProviderType providerType,
    String providerId,
    String modelId,
    String systemPrompt,
    JsonNode defaultOptions,
    JsonNode toolBindings,
    JsonNode costProfile,
    Boolean syncOnly,
    Integer maxTokens,
    String createdBy,
    List<AgentCapabilityRequest> capabilities) {}
