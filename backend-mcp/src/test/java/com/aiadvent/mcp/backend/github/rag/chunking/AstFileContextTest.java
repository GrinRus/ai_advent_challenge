package com.aiadvent.mcp.backend.github.rag.chunking;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class AstFileContextTest {

  @Test
  void returnsPerfectMatchWhenChunkInsideSymbol() {
    AstSymbolMetadata method =
        new AstSymbolMetadata(
            "Demo.process",
            "method",
            "public",
            "void process()",
            null,
            false,
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            10,
            30);
    AstFileContext context = new AstFileContext(List.of(method));

    assertThat(context.symbolForRange(12, 15)).contains(method);
  }

  @Test
  void fallsBackToSymbolContainingStartLine() {
    AstSymbolMetadata method =
        new AstSymbolMetadata(
            "Demo.process",
            "method",
            "public",
            "void process()",
            null,
            false,
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            10,
            20);
    AstFileContext context = new AstFileContext(List.of(method));

    assertThat(context.symbolForRange(12, 25)).contains(method);
  }

  @Test
  void returnsOverlappingSymbolWhenStartNotContained() {
    AstSymbolMetadata method =
        new AstSymbolMetadata(
            "Demo.process",
            "method",
            "public",
            "void process()",
            null,
            false,
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            10,
            20);
    AstFileContext context = new AstFileContext(List.of(method));

    assertThat(context.symbolForRange(5, 12)).contains(method);
  }

  @Test
  void returnsFirstSymbolWhenNoOverlap() {
    AstSymbolMetadata fileSymbol =
        new AstSymbolMetadata(
            "Demo.java",
            "file",
            "public",
            "Demo.java",
            null,
            false,
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            1,
            100);
    AstSymbolMetadata method =
        new AstSymbolMetadata(
            "Demo.process",
            "method",
            "public",
            "void process()",
            null,
            false,
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            10,
            20);
    AstFileContext context = new AstFileContext(List.of(fileSymbol, method));

    assertThat(context.symbolForRange(101, 110)).contains(fileSymbol);
  }
}
