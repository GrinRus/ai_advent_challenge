package com.aiadvent.backend.flow.config;

import com.aiadvent.backend.chat.provider.model.ChatRequestOverrides;
import java.util.List;
import java.util.UUID;

public record FlowStepConfig(
    String id,
    String name,
    UUID agentVersionId,
    String prompt,
    ChatRequestOverrides overrides,
    FlowInteractionConfig interaction,
    List<MemoryReadConfig> memoryReads,
    List<MemoryWriteConfig> memoryWrites,
    FlowStepTransitions transitions,
    int maxAttempts) {

  public FlowStepConfig {
    if (id == null || id.isBlank()) {
      throw new IllegalArgumentException("Step id must not be blank");
    }
    if (agentVersionId == null) {
      throw new IllegalArgumentException("Step '" + id + "' must define agentVersionId");
    }
    prompt = prompt != null ? prompt.trim() : "";
    overrides = overrides != null ? overrides : ChatRequestOverrides.empty();
    interaction = interaction;
    memoryReads = memoryReads != null ? List.copyOf(memoryReads) : List.of();
    memoryWrites = memoryWrites != null ? List.copyOf(memoryWrites) : List.of();
    transitions = transitions != null ? transitions : FlowStepTransitions.defaults();
    maxAttempts = maxAttempts <= 0 ? 1 : maxAttempts;
  }
}
