package com.aiadvent.backend.flow.api;

import com.aiadvent.backend.flow.agent.options.AgentInvocationOptions;
import java.util.List;

public record AgentConstructorValidateResponse(
    AgentInvocationOptions normalized, List<ValidationIssue> issues) {}

