package com.aiadvent.mcp.backend.github.rag;

import java.util.Locale;
import org.springframework.util.StringUtils;

/**
 * Represents a single conversation turn passed to the RAG search pipeline.
 */
public record RepoRagSearchConversationTurn(String role, String content) {

  public RepoRagSearchConversationTurn {
    if (!StringUtils.hasText(content)) {
      throw new IllegalArgumentException("content must not be blank");
    }
  }

  public String normalizedRole() {
    if (!StringUtils.hasText(role)) {
      return "user";
    }
    return role.trim().toLowerCase(Locale.ROOT);
  }
}

