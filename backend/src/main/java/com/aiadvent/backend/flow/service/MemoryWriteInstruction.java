package com.aiadvent.backend.flow.service;

import com.aiadvent.backend.flow.memory.FlowMemorySourceType;
import com.fasterxml.jackson.databind.JsonNode;

public record MemoryWriteInstruction(String channel, JsonNode payload, FlowMemorySourceType sourceType) {

  public MemoryWriteInstruction {
    if (channel == null || channel.isBlank()) {
      throw new IllegalArgumentException("channel must not be blank");
    }
    if (payload == null) {
      throw new IllegalArgumentException("payload must not be null");
    }
    sourceType = sourceType != null ? sourceType : FlowMemorySourceType.SYSTEM;
  }
}
