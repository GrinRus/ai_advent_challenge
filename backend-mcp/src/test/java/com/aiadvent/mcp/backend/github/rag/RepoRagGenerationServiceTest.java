package com.aiadvent.mcp.backend.github.rag;

import static org.assertj.core.api.Assertions.assertThat;

import com.aiadvent.mcp.backend.config.GitHubRagProperties;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.core.io.DefaultResourceLoader;

class RepoRagGenerationServiceTest {

  private RepoRagGenerationService generationService;

  @BeforeEach
  void setUp() {
    generationService =
        new RepoRagGenerationService(new GitHubRagProperties(), new DefaultResourceLoader());
  }

  @Test
  void augmentedPromptMatchesContextualQueryAugmenterOutput() {
    Query query = Query.builder().text("Как собрать проект?").build();
    Document document =
        Document.builder()
            .id("1")
            .text("gradlew build запускает тесты")
            .metadata(java.util.Map.of("file_path", "README.md"))
            .build();

    RepoRagGenerationService.GenerationResult result =
        generationService.generate(
            new RepoRagGenerationService.GenerationCommand(
                query,
                List.of(document),
                "owner",
                "repo",
                "ru",
                true,
                RepoRagResponseChannel.BOTH));

    assertThat(result.contextMissing()).isFalse();
    assertThat(result.rawAnswer()).isEqualTo(result.rawAugmentedPrompt());
    assertThat(result.summary()).isEqualTo(result.summaryAugmentedPrompt());
    assertThat(result.rawAugmentedPrompt())
        .contains("Ты выступаешь экспертом")
        .contains("# Контекст")
        .contains("gradlew build запускает тесты");
  }
}
