package com.aiadvent.mcp.backend.github.rag;

import java.util.Locale;

public enum RepoRagResponseChannel {
  SUMMARY,
  RAW,
  BOTH;

  public boolean includeSummary() {
    return this == SUMMARY || this == BOTH;
  }

  public boolean includeRaw() {
    return this == RAW || this == BOTH;
  }

  public static RepoRagResponseChannel fromToken(String token) {
    if (token == null || token.isBlank()) {
      return BOTH;
    }
    String normalized = token.trim().toLowerCase(Locale.ROOT);
    return switch (normalized) {
      case "summary", "short" -> SUMMARY;
      case "raw", "full" -> RAW;
      default -> BOTH;
    };
  }
}
