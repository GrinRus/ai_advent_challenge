package com.aiadvent.backend.mcp.util;

import org.springframework.util.StringUtils;

public final class McpToolNameSanitizer {

  private McpToolNameSanitizer() {}

  public static String sanitize(String name) {
    if (!StringUtils.hasText(name)) {
      return null;
    }
    String trimmed = name.trim();
    String sanitized =
        trimmed.replaceAll(
            "[^\\p{IsHan}\\p{InCJK_Unified_Ideographs}\\p{InCJK_Compatibility_Ideographs}a-zA-Z0-9_-]",
            "");
    sanitized = sanitized.replace('-', '_');
    return StringUtils.hasText(sanitized) ? sanitized : null;
  }
}
