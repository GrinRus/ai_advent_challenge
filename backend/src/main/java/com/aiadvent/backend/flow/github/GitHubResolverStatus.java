package com.aiadvent.backend.flow.github;

public enum GitHubResolverStatus {
  RESOLVED,
  NEEDS_CLARIFICATION,
  UNSUPPORTED,
  INVALID;

  public static GitHubResolverStatus fromString(String value) {
    if (value == null || value.isBlank()) {
      return INVALID;
    }
    return switch (value.trim().toLowerCase()) {
      case "resolved", "ok", "success" -> RESOLVED;
      case "clarify", "clarification", "needs_clarification", "needs-clarification", "waiting_user" -> NEEDS_CLARIFICATION;
      case "unsupported", "not_supported" -> UNSUPPORTED;
      default -> INVALID;
    };
  }
}

