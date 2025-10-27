package com.aiadvent.backend.flow.api;

import com.aiadvent.backend.flow.agent.options.AgentInvocationOptions;

public record AgentConstructorPreviewRequest(
    AgentInvocationOptions proposed, AgentInvocationOptions baseline, String promptSample) {}

