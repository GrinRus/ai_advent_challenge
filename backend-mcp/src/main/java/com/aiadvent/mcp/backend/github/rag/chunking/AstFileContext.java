package com.aiadvent.mcp.backend.github.rag.chunking;

import com.aiadvent.mcp.backend.github.rag.ast.TreeSitterParser;
import java.util.List;
import java.util.Optional;

public record AstFileContext(List<AstSymbolMetadata> symbols) {

  public static AstFileContext create(String content, String language) {
    return new TreeSitterParser().parse(content, language, null).orElse(null);
  }

  public Optional<AstSymbolMetadata> symbolForRange(int startLine, int endLine) {
    if (symbols == null || symbols.isEmpty()) {
      return Optional.empty();
    }
    AstSymbolMetadata perfect = null;
    AstSymbolMetadata startMatch = null;
    AstSymbolMetadata overlap = null;
    for (AstSymbolMetadata symbol : symbols) {
      if (symbol.lineStart() <= startLine && symbol.lineEnd() >= endLine) {
        if (perfect == null
            || span(symbol) < span(perfect)) {
          perfect = symbol;
        }
      }
      if (startMatch == null && symbol.lineStart() <= startLine && symbol.lineEnd() >= startLine) {
        startMatch = symbol;
      }
      if (overlap == null && symbol.lineStart() <= endLine && symbol.lineEnd() >= startLine) {
        overlap = symbol;
      }
    }
    AstSymbolMetadata candidate = perfect != null ? perfect : startMatch != null ? startMatch : overlap;
    if (candidate == null && !symbols.isEmpty()) {
      candidate = symbols.get(0);
    }
    return Optional.ofNullable(candidate);
  }

  private int span(AstSymbolMetadata symbol) {
    return symbol.lineEnd() - symbol.lineStart();
  }
}
