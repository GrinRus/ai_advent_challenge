package com.aiadvent.mcp.backend.github.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.aiadvent.mcp.backend.config.GitHubRagProperties;
import com.aiadvent.mcp.backend.github.GitHubRepositoryFetchRegistry;
import com.aiadvent.mcp.backend.github.rag.RepoRagSearchConversationTurn;
import com.aiadvent.mcp.backend.github.rag.persistence.RepoRagNamespaceStateEntity;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class RepoRagToolInputSanitizerTest {

  @Mock private GitHubRepositoryFetchRegistry fetchRegistry;
  @Mock private RepoRagNamespaceStateService namespaceStateService;

  private GitHubRagProperties properties;
  private RepoRagToolInputSanitizer sanitizer;

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);
    properties = new GitHubRagProperties();
    sanitizer = new RepoRagToolInputSanitizer(properties, fetchRegistry, namespaceStateService, null);
  }

  @Test
  void sanitizeSearchFillsOwnerAndNormalizesFilters() {
    GitHubRepositoryFetchRegistry.LastFetchContext context =
        new GitHubRepositoryFetchRegistry.LastFetchContext(
            "Example", "Repo", "ref", "sha", "ws", Instant.now());
    RepoRagNamespaceStateEntity state = new RepoRagNamespaceStateEntity();
    state.setRepoOwner("example");
    state.setRepoName("repo");
    state.setReady(true);
    when(fetchRegistry.latest()).thenReturn(Optional.of(context));
    when(namespaceStateService.findByRepoOwnerAndRepoName("example", "repo"))
        .thenReturn(Optional.of(state));

    RepoRagSearchFilters filters =
        new RepoRagSearchFilters(List.of("Python", "JS"), List.of("**/*", "src/**/*.java"));
    RepoRagTools.RepoRagSearchInput input =
        new RepoRagTools.RepoRagSearchInput(
            null,
            null,
            "  Explain RAG  ",
            100,
            null,
            null,
            Map.of("PY", 2.0d),
            null,
            null,
            null,
            null,
            null,
            null,
            "graph",
            filters,
            null,
            List.<RepoRagSearchConversationTurn>of(),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            "Ответ {{query}} по {{unknown}}");

    RepoRagToolInputSanitizer.SanitizationResult<RepoRagTools.RepoRagSearchInput> result =
        sanitizer.sanitizeSearch(input);

    RepoRagTools.RepoRagSearchInput sanitized = result.value();
    assertThat(sanitized.repoOwner()).isEqualTo("example");
    assertThat(sanitized.repoName()).isEqualTo("repo");
    assertThat(sanitized.rawQuery()).isEqualTo("Explain RAG");
    assertThat(sanitized.neighborRadius())
        .isEqualTo(properties.getPostProcessing().getNeighbor().getDefaultRadius());
    assertThat(sanitized.neighborLimit())
        .isEqualTo(properties.getPostProcessing().getNeighbor().getDefaultLimit());
    assertThat(sanitized.neighborStrategy()).isEqualTo("CALL_GRAPH");
    assertThat(sanitized.filters()).isNotNull();
    assertThat(sanitized.filters().languages()).containsExactly("python", "javascript");
    assertThat(sanitized.filters().pathGlobs()).containsExactly("src/**/*.java");
    assertThat(sanitized.minScoreByLanguage()).containsEntry("python", 0.99d);
    assertThat(sanitized.instructionsTemplate()).isEqualTo("Ответ {{rawQuery}} по");
    assertThat(result.warnings()).isNotEmpty();
  }

  @Test
  void sanitizeGlobalFixesPlaceholdersAndLocales() {
    GitHubRepositoryFetchRegistry.LastFetchContext context =
        new GitHubRepositoryFetchRegistry.LastFetchContext(
            "Org", "Service", "ref", "sha", "ws", Instant.now());
    RepoRagNamespaceStateEntity state = new RepoRagNamespaceStateEntity();
    state.setRepoOwner("org");
    state.setRepoName("service");
    state.setReady(true);
    when(fetchRegistry.latest()).thenReturn(Optional.of(context));
    when(namespaceStateService.findByRepoOwnerAndRepoName("org", "service"))
        .thenReturn(Optional.of(state));

    RepoRagTools.RepoRagGlobalSearchInput input =
        new RepoRagTools.RepoRagGlobalSearchInput(
            " Что такое MCP? ",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            "callgraph",
            null,
            null,
            List.<RepoRagSearchConversationTurn>of(),
            null,
            null,
            null,
            "английский",
            null,
            null,
            null,
            "Answer {{query}} -> {{unknown}}",
            null,
            null);

    RepoRagToolInputSanitizer.SanitizationResult<RepoRagTools.RepoRagGlobalSearchInput> result =
        sanitizer.sanitizeGlobal(input);

    RepoRagTools.RepoRagGlobalSearchInput sanitized = result.value();
    assertThat(sanitized.rawQuery()).isEqualTo("Что такое MCP?");
    assertThat(sanitized.neighborStrategy()).isEqualTo("CALL_GRAPH");
    assertThat(sanitized.translateTo()).isEqualTo("en");
    assertThat(sanitized.generationLocale()).isEqualTo("en");
    assertThat(sanitized.displayRepoOwner()).isEqualTo("org");
    assertThat(sanitized.displayRepoName()).isEqualTo("service");
    assertThat(sanitized.instructionsTemplate()).isEqualTo("Answer {{rawQuery}} ->");
    assertThat(result.warnings()).hasSizeGreaterThanOrEqualTo(3);
  }
}
