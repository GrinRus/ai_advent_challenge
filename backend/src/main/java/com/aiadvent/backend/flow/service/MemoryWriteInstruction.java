package com.aiadvent.backend.flow.service;

import com.fasterxml.jackson.databind.JsonNode;

public record MemoryWriteInstruction(String channel, JsonNode payload) {

  public MemoryWriteInstruction {
    if (channel == null || channel.isBlank()) {
      throw new IllegalArgumentException("channel must not be blank");
    }
    if (payload == null) {
      throw new IllegalArgumentException("payload must not be null");
    }
  }
}
