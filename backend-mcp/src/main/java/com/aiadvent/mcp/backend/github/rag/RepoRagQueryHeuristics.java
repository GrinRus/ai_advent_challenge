package com.aiadvent.mcp.backend.github.rag;

import java.util.Locale;
import org.springframework.util.StringUtils;

/** Utility heuristics for classifying incoming RAG queries. */
final class RepoRagQueryHeuristics {

  private RepoRagQueryHeuristics() {}

  static boolean isCodeIdentifier(String text) {
    if (!StringUtils.hasText(text)) {
      return false;
    }
    String candidate = text.trim();
    if (candidate.length() < 2 || candidate.length() > 120) {
      return false;
    }
    if (candidate.chars().anyMatch(Character::isWhitespace)) {
      return false;
    }
    boolean hasCamelCase = hasCamelCase(candidate);
    boolean hasSeparator =
        candidate.indexOf('.') >= 0
            || candidate.indexOf('/') >= 0
            || candidate.indexOf('_') >= 0
            || candidate.indexOf('-') >= 0
            || candidate.contains("::");
    boolean isUpperSnake =
        candidate.equals(candidate.toUpperCase(Locale.ROOT)) && candidate.contains("_");
    boolean hasDigit = candidate.chars().anyMatch(Character::isDigit);
    return hasCamelCase || hasSeparator || isUpperSnake || hasDigit;
  }

  private static boolean hasCamelCase(String candidate) {
    boolean seenUpper = false;
    boolean seenLower = false;
    for (char ch : candidate.toCharArray()) {
      if (Character.isUpperCase(ch)) {
        seenUpper = true;
      } else if (Character.isLowerCase(ch)) {
        seenLower = true;
      }
      if (seenUpper && seenLower) {
        return true;
      }
    }
    return false;
  }
}
