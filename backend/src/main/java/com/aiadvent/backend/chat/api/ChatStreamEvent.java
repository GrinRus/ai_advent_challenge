package com.aiadvent.backend.chat.api;

import java.util.UUID;

public record ChatStreamEvent(UUID sessionId, String type, String content, boolean newSession) {

  public static ChatStreamEvent session(UUID sessionId, boolean newSession) {
    return new ChatStreamEvent(sessionId, "session", null, newSession);
  }

  public static ChatStreamEvent token(UUID sessionId, String content) {
    return new ChatStreamEvent(sessionId, "token", content, false);
  }

  public static ChatStreamEvent complete(UUID sessionId, String content) {
    return new ChatStreamEvent(sessionId, "complete", content, false);
  }

  public static ChatStreamEvent error(UUID sessionId, String content) {
    return new ChatStreamEvent(sessionId, "error", content, false);
  }
}
