package com.aiadvent.backend.flow.api;

import com.aiadvent.backend.flow.agent.options.AgentInvocationOptions;
import java.util.List;

public record AgentVersionUpdateRequest(
    String systemPrompt,
    AgentInvocationOptions invocationOptions,
    Boolean syncOnly,
    Integer maxTokens,
    String updatedBy,
    List<AgentCapabilityRequest> capabilities) {}
