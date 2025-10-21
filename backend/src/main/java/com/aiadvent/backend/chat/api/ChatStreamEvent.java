package com.aiadvent.backend.chat.api;

import java.util.UUID;

public record ChatStreamEvent(
    UUID sessionId,
    String type,
    String content,
    boolean newSession,
    String provider,
    String model,
    StructuredSyncUsageStats usage,
    UsageCostDetails cost) {

  public static ChatStreamEvent session(
      UUID sessionId, boolean newSession, String provider, String model) {
    return new ChatStreamEvent(sessionId, "session", null, newSession, provider, model, null, null);
  }

  public static ChatStreamEvent token(UUID sessionId, String content, String provider, String model) {
    return new ChatStreamEvent(sessionId, "token", content, false, provider, model, null, null);
  }

  public static ChatStreamEvent complete(
      UUID sessionId,
      String content,
      String provider,
      String model,
      StructuredSyncUsageStats usage,
      UsageCostDetails cost) {
    return new ChatStreamEvent(sessionId, "complete", content, false, provider, model, usage, cost);
  }

  public static ChatStreamEvent error(UUID sessionId, String content, String provider, String model) {
    return new ChatStreamEvent(sessionId, "error", content, false, provider, model, null, null);
  }
}
