package com.aiadvent.backend.flow.config;

public record MemoryReadConfig(String channel, int limit) {

  public MemoryReadConfig {
    if (channel == null || channel.isBlank()) {
      throw new IllegalArgumentException("Memory read channel must not be blank");
    }
    limit = limit <= 0 ? 10 : limit;
  }
}
