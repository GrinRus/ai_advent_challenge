package com.aiadvent.backend.insight;

public enum InsightSessionType {
  CHAT,
  FLOW;

  public static InsightSessionType fromString(String value) {
    if (value == null) {
      throw new IllegalArgumentException("Session type must be provided");
    }
    return switch (value.trim().toUpperCase()) {
      case "CHAT" -> CHAT;
      case "FLOW" -> FLOW;
      default -> throw new IllegalArgumentException("Unsupported session type: " + value);
    };
  }
}
