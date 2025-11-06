package com.aiadvent.mcp.backend.coding;

import java.util.Objects;

/**
 * A lightweight snapshot of a file fragment provided to the coding assistant as additional
 * context for patch generation or review.
 */
public record ContextSnippet(
    String path, String encoding, boolean binary, String content, String base64, boolean truncated) {

  public ContextSnippet {
    path = Objects.requireNonNull(path, "path").trim();
    encoding = encoding == null ? "" : encoding;
    content = content == null ? "" : content;
    base64 = base64 == null ? "" : base64;
  }
}
