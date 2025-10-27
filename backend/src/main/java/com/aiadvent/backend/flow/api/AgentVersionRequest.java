package com.aiadvent.backend.flow.api;

import com.aiadvent.backend.chat.config.ChatProviderType;
import com.aiadvent.backend.flow.agent.options.AgentInvocationOptions;
import java.util.List;

public record AgentVersionRequest(
    ChatProviderType providerType,
    String providerId,
    String modelId,
    String systemPrompt,
    AgentInvocationOptions invocationOptions,
    Boolean syncOnly,
    Integer maxTokens,
    String createdBy,
    List<AgentCapabilityRequest> capabilities) {}
