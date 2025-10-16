package com.aiadvent.backend.chat.provider.model;

public record ChatRequestOverrides(Double temperature, Double topP, Integer maxTokens) {

  public static ChatRequestOverrides empty() {
    return new ChatRequestOverrides(null, null, null);
  }
}
