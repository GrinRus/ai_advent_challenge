package com.aiadvent.mcp.backend.github.rag;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RepoRagQueryHeuristicsTest {

  @Test
  void detectsCamelCaseIdentifiers() {
    assertThat(RepoRagQueryHeuristics.isCodeIdentifier("PatchGenerationService")).isTrue();
  }

  @Test
  void rejectsSentences() {
    assertThat(RepoRagQueryHeuristics.isCodeIdentifier("что такое сервис?")).isFalse();
  }
}
