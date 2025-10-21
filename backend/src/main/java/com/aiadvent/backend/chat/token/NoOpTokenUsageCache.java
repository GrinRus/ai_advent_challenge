package com.aiadvent.backend.chat.token;

enum NoOpTokenUsageCache implements TokenUsageCache {
  INSTANCE;

  @Override
  public Integer get(String key) {
    return null;
  }

  @Override
  public void put(String key, int value) {
    // deliberately no-op
  }
}

