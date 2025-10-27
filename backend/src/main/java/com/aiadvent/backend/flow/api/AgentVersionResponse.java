package com.aiadvent.backend.flow.api;

import com.aiadvent.backend.chat.config.ChatProviderType;
import com.aiadvent.backend.flow.agent.options.AgentInvocationOptions;
import com.aiadvent.backend.flow.domain.AgentVersionStatus;
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
    AgentInvocationOptions invocationOptions,
    boolean syncOnly,
    Integer maxTokens,
    String createdBy,
    String updatedBy,
    Instant createdAt,
    Instant publishedAt,
    List<AgentCapabilityResponse> capabilities) {}
