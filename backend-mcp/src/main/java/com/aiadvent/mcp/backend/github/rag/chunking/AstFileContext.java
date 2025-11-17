package com.aiadvent.mcp.backend.github.rag.chunking;

import java.util.List;

import java.util.List;
import java.util.Optional;

public record AstFileContext(List<AstSymbolMetadata> symbols) {

  public static AstFileContext create(String content, String language) {
    return null;
  }

  public Optional<AstSymbolMetadata> symbolForRange(int startLine, int endLine) {
    if (symbols == null || symbols.isEmpty()) {
      return Optional.empty();
    }
    return symbols.stream()
        .filter(symbol -> symbol.lineStart() <= startLine && symbol.lineEnd() >= endLine)
        .findFirst();
  }
}
