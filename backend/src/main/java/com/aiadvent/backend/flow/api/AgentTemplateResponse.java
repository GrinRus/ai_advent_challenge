package com.aiadvent.backend.flow.api;

import com.aiadvent.backend.flow.agent.options.AgentInvocationOptions;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

public record AgentTemplateResponse(List<AgentTemplate> templates) {

  public static AgentTemplateResponse empty() {
    return new AgentTemplateResponse(List.of());
  }

  public record AgentTemplate(
      String identifier,
      String displayName,
      String description,
      AgentInvocationOptions invocationOptions,
      boolean syncOnly,
      Integer maxTokens,
      List<AgentTemplateCapability> capabilities) {}

  public record AgentTemplateCapability(String capability, JsonNode payload) {}
}
