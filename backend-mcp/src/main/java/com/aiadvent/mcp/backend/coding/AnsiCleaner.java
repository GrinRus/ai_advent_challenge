package com.aiadvent.mcp.backend.coding;

import java.util.regex.Pattern;

/**
 * Utility to strip ANSI escape sequences from CLI output before JSON parsing.
 */
final class AnsiCleaner {

  private static final Pattern ANSI_PATTERN = Pattern.compile("\u001B\\[[;\\d]*[ -/]*[@-~]");

  private AnsiCleaner() {}

  static String strip(String text) {
    if (text == null || text.isEmpty()) {
      return text;
    }
    return ANSI_PATTERN.matcher(text).replaceAll("");
  }
}
