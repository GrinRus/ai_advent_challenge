package com.aiadvent.mcp.backend.github.rag;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RepoRagQueryHeuristicsTest {

  @Test
  void detectsCamelCaseIdentifiers() {
    assertThat(RepoRagQueryHeuristics.isCodeIdentifier("PatchGenerationService")).isTrue();
  }

  @Test
  void detectsIdentifiersWithKeywordPrefix() {
    assertThat(RepoRagQueryHeuristics.isCodeIdentifier("class OpenAiChatProviderAdapter")).isTrue();
    assertThat(RepoRagQueryHeuristics.isCodeIdentifier("public static func patch_generation_service")).isTrue();
  }

  @Test
  void rejectsSentences() {
    assertThat(RepoRagQueryHeuristics.isCodeIdentifier("что такое сервис?")).isFalse();
  }

  @Test
  void rejectsMultiWordDescriptionsEvenWithKeyword() {
    assertThat(RepoRagQueryHeuristics.isCodeIdentifier("class diagrams for scheduler module")).isFalse();
  }
}
