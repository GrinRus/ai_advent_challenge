package com.aiadvent.backend.chat.api;

import java.util.UUID;

import java.util.List;

public record ChatStreamEvent(
    UUID sessionId,
    String type,
    String content,
    boolean newSession,
    String provider,
    String model,
    List<String> tools,
    StructuredSyncUsageStats usage,
    UsageCostDetails cost,
    String usageSource) {

  public static ChatStreamEvent session(
      UUID sessionId, boolean newSession, String provider, String model) {
    return new ChatStreamEvent(
        sessionId, "session", null, newSession, provider, model, List.of(), null, null, null);
  }

  public static ChatStreamEvent token(UUID sessionId, String content, String provider, String model) {
    return new ChatStreamEvent(
        sessionId, "token", content, false, provider, model, List.of(), null, null, null);
  }

  public static ChatStreamEvent complete(
      UUID sessionId,
      String content,
      String provider,
      String model,
      List<String> tools,
      StructuredSyncUsageStats usage,
      UsageCostDetails cost,
      String usageSource) {
    return new ChatStreamEvent(
        sessionId, "complete", content, false, provider, model, tools, usage, cost, usageSource);
  }

  public static ChatStreamEvent error(UUID sessionId, String content, String provider, String model) {
    return new ChatStreamEvent(
        sessionId, "error", content, false, provider, model, List.of(), null, null, null);
  }
}
