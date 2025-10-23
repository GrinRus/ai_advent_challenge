package com.aiadvent.backend.flow.config;

import com.fasterxml.jackson.databind.JsonNode;

public record MemoryWriteConfig(String channel, MemoryWriteMode mode, JsonNode payload) {

  public MemoryWriteConfig {
    if (channel == null || channel.isBlank()) {
      throw new IllegalArgumentException("Memory write channel must not be blank");
    }
    mode = mode != null ? mode : MemoryWriteMode.AGENT_OUTPUT;
  }
}
