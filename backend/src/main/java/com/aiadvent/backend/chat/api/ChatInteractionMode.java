package com.aiadvent.backend.chat.api;

import org.springframework.util.StringUtils;

public enum ChatInteractionMode {
  DEFAULT,
  RESEARCH;

  public static ChatInteractionMode from(String raw) {
    if (!StringUtils.hasText(raw)) {
      return DEFAULT;
    }
    String normalized = raw.trim().toLowerCase();
    return switch (normalized) {
      case "research" -> RESEARCH;
      default -> DEFAULT;
    };
  }

  public boolean isResearch() {
    return this == RESEARCH;
  }
}
