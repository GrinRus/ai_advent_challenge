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
    if (!candidate.chars().anyMatch(Character::isWhitespace)) {
      return looksLikeIdentifier(candidate);
    }
    String[] tokens = candidate.split("\\s+");
    if (tokens.length == 0 || tokens.length > 6) {
      return false;
    }
    String identifierToken = null;
    for (String token : tokens) {
      if (!StringUtils.hasText(token)) {
        continue;
      }
      String normalized = token.trim();
      String lower = normalized.toLowerCase(Locale.ROOT);
      if (isPrefixKeyword(lower)) {
        continue;
      }
      String stripped = stripDelimiters(normalized);
      if (!StringUtils.hasText(stripped)) {
        continue;
      }
      if (identifierToken != null) {
        return false;
      }
      identifierToken = stripped;
    }
    return looksLikeIdentifier(identifierToken);
  }

  private static boolean isPrefixKeyword(String token) {
    if (!StringUtils.hasText(token)) {
      return false;
    }
    return switch (token) {
      case "class",
          "interface",
          "struct",
          "enum",
          "trait",
          "record",
          "type",
          "module",
          "func",
          "function",
          "method",
          "def",
          "var",
          "let",
          "const",
          "new",
          "public",
          "private",
          "protected",
          "static",
          "final",
          "класс",
          "структура",
          "функция",
          "метод" -> true;
      default -> false;
    };
  }

  private static boolean looksLikeIdentifier(String candidate) {
    if (!StringUtils.hasText(candidate)) {
      return false;
    }
    String trimmed = candidate.trim();
    if (trimmed.length() < 2 || trimmed.length() > 120) {
      return false;
    }
    if (trimmed.chars().anyMatch(Character::isWhitespace)) {
      return false;
    }
    boolean hasCamelCase = hasCamelCase(trimmed);
    boolean hasSeparator =
        trimmed.indexOf('.') >= 0
            || trimmed.indexOf('/') >= 0
            || trimmed.indexOf('_') >= 0
            || trimmed.indexOf('-') >= 0
            || trimmed.contains("::");
    boolean isUpperSnake =
      trimmed.equals(trimmed.toUpperCase(Locale.ROOT)) && trimmed.contains("_");
    boolean hasDigit = trimmed.chars().anyMatch(Character::isDigit);
    return hasCamelCase || hasSeparator || isUpperSnake || hasDigit;
  }

  private static String stripDelimiters(String token) {
    if (!StringUtils.hasText(token)) {
      return token;
    }
    int start = 0;
    int end = token.length();
    while (start < end && !isIdentifierChar(token.charAt(start))) {
      start++;
    }
    while (end > start && !isIdentifierChar(token.charAt(end - 1))) {
      end--;
    }
    return start >= end ? "" : token.substring(start, end);
  }

  private static boolean isIdentifierChar(char ch) {
    return Character.isLetterOrDigit(ch)
        || ch == '.'
        || ch == '_'
        || ch == '-'
        || ch == '/'
        || ch == ':'
        || ch == '$';
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
