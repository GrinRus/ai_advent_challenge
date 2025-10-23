package com.aiadvent.backend.flow.service;

public record MemoryReadInstruction(String channel, int limit) {

  public MemoryReadInstruction {
    if (channel == null || channel.isBlank()) {
      throw new IllegalArgumentException("channel must not be blank");
    }
    limit = limit <= 0 ? Integer.MAX_VALUE : limit;
  }
}
