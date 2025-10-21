package com.aiadvent.backend.chat.token;

public interface TokenUsageCache {

  Integer get(String key);

  void put(String key, int value);

  static TokenUsageCache noOp() {
    return NoOpTokenUsageCache.INSTANCE;
  }
}
