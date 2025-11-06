package com.aiadvent.mcp.backend.coding;

/**
 * Token accounting for patch generation/review completions.
 */
public record PatchUsage(int promptTokens, int completionTokens) {

  public PatchUsage {
    if (promptTokens < 0) {
      throw new IllegalArgumentException("promptTokens must be >= 0");
    }
    if (completionTokens < 0) {
      throw new IllegalArgumentException("completionTokens must be >= 0");
    }
  }

  public static PatchUsage empty() {
    return new PatchUsage(0, 0);
  }
}
