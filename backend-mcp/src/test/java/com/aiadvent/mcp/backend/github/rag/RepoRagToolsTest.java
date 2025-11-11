package com.aiadvent.mcp.backend.github.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aiadvent.mcp.backend.github.GitHubRepositoryFetchRegistry;
import com.aiadvent.mcp.backend.github.rag.persistence.RepoRagNamespaceStateEntity;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class RepoRagToolsTest {

  @Mock private RepoRagStatusService statusService;
  @Mock private RepoRagSearchService searchService;
  @Mock private RepoRagNamespaceStateService namespaceStateService;
  @Mock private GitHubRepositoryFetchRegistry fetchRegistry;

  private RepoRagTools tools;

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);
    tools = new RepoRagTools(statusService, searchService, namespaceStateService, fetchRegistry);
  }

  @Test
  void ragSearchSimpleUsesLastFetchContext() {
    GitHubRepositoryFetchRegistry.LastFetchContext context =
        new GitHubRepositoryFetchRegistry.LastFetchContext(
            "Example", "Repo", "refs/heads/main", "sha", "ws", Instant.now());
    when(fetchRegistry.latest()).thenReturn(Optional.of(context));
    RepoRagNamespaceStateEntity entity = new RepoRagNamespaceStateEntity();
    entity.setNamespace("repo:example/repo");
    entity.setRepoOwner("example");
    entity.setRepoName("repo");
    entity.setReady(true);
    when(namespaceStateService.findByRepoOwnerAndRepoName("example", "repo"))
        .thenReturn(Optional.of(entity));
    RepoRagSearchService.SearchResponse response =
        new RepoRagSearchService.SearchResponse(
            List.of(), false, "prompt", "instructions", false, true, null, List.of());
    when(searchService.search(any())).thenReturn(response);

    RepoRagTools.RepoRagSearchResponse result =
        tools.ragSearchSimple(new RepoRagTools.RepoRagSimpleSearchInput("Что нового?"));

    assertThat(result.instructions()).isEqualTo("instructions");
    ArgumentCaptor<RepoRagSearchService.SearchCommand> captor =
        ArgumentCaptor.forClass(RepoRagSearchService.SearchCommand.class);
    verify(searchService).search(captor.capture());
    assertThat(captor.getValue().repoOwner()).isEqualTo("example");
    assertThat(captor.getValue().repoName()).isEqualTo("repo");
  }

  @Test
  void ragSearchSimpleRequiresFetch() {
    when(fetchRegistry.latest()).thenReturn(Optional.empty());

    assertThatThrownBy(() -> tools.ragSearchSimple(new RepoRagTools.RepoRagSimpleSearchInput("q")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("github.repository_fetch");
  }

  @Test
  void ragSearchGlobalUsesService() {
    RepoRagSearchService.SearchResponse response =
        new RepoRagSearchService.SearchResponse(
            List.of(), false, "prompt", "instructions", false, false, null, List.of());
    when(searchService.searchGlobal(any())).thenReturn(response);

    RepoRagTools.RepoRagGlobalSearchInput input =
        new RepoRagTools.RepoRagGlobalSearchInput(
            "How to build?",
            5,
            null,
            null,
            null,
            null,
            null,
            null,
            List.of(),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            "global-owner",
            "mixed");

    RepoRagTools.RepoRagSearchResponse result = tools.ragSearchGlobal(input);
    assertThat(result.instructions()).isEqualTo("instructions");
    ArgumentCaptor<RepoRagSearchService.GlobalSearchCommand> captor =
        ArgumentCaptor.forClass(RepoRagSearchService.GlobalSearchCommand.class);
    verify(searchService).searchGlobal(captor.capture());
    assertThat(captor.getValue().displayRepoOwner()).isEqualTo("global-owner");
  }
}
