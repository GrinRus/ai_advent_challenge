package com.aiadvent.mcp.backend.github.rag.chunking;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import org.springframework.util.StringUtils;

public record Chunk(
    String text,
    int lineStart,
    int lineEnd,
    String language,
    String summary,
    String hash,
    String parentSymbol,
    int overlapLines,
    AstSymbolMetadata astMetadata) {

  public static Chunk from(
      String rawText,
      int lineStart,
      int lineEnd,
      String language,
      ParentSymbolResolver parentSymbolResolver,
      int overlapLines,
      AstSymbolMetadata astMetadata) {
    String normalized = normalize(rawText);
    if (!StringUtils.hasText(normalized)) {
      return null;
    }
    String summary = buildSummary(normalized);
    String hash = sha256(normalized);
    String parentSymbol = parentSymbolResolver != null ? parentSymbolResolver.resolve(lineStart) : null;
    return new Chunk(
        normalized,
        lineStart,
        lineEnd,
        language,
        summary,
        hash,
        parentSymbol,
        Math.max(0, overlapLines),
        astMetadata);
  }

  private static String normalize(String value) {
    if (value == null) {
      return "";
    }
    return value.trim();
  }

  private static String buildSummary(String text) {
    if (!StringUtils.hasText(text)) {
      return "";
    }
    return text.lines().limit(2).reduce((a, b) -> a + " " + b).orElse(text).trim();
  }

  private static String sha256(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
      StringBuilder builder = new StringBuilder(hash.length * 2);
      for (byte b : hash) {
        builder.append(String.format(Locale.ROOT, "%02x", b));
      }
      return builder.toString();
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 is not available", ex);
    }
  }
}
