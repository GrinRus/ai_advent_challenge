package com.aiadvent.backend.flow.service;

import com.aiadvent.backend.flow.domain.AgentVersion;
import com.fasterxml.jackson.databind.JsonNode;
import com.aiadvent.backend.chat.provider.model.ChatRequestOverrides;
import java.util.List;
import java.util.UUID;

public record AgentInvocationRequest(
    UUID flowSessionId,
    UUID stepId,
    AgentVersion agentVersion,
    String userPrompt,
    JsonNode inputContext,
    JsonNode launchParameters,
    ChatRequestOverrides stepOverrides,
    ChatRequestOverrides sessionOverrides,
    List<MemoryReadInstruction> memoryReads,
    List<MemoryWriteInstruction> memoryWrites) {

  public AgentInvocationRequest {
    if (flowSessionId == null) {
      throw new IllegalArgumentException("flowSessionId must not be null");
    }
    if (agentVersion == null) {
      throw new IllegalArgumentException("agentVersion must not be null");
    }
    if (userPrompt == null || userPrompt.isBlank()) {
      throw new IllegalArgumentException("userPrompt must not be blank");
    }
    memoryReads = memoryReads == null ? List.of() : List.copyOf(memoryReads);
    memoryWrites = memoryWrites == null ? List.of() : List.copyOf(memoryWrites);
    if (stepOverrides == null) {
      stepOverrides = ChatRequestOverrides.empty();
    }
  }
}
