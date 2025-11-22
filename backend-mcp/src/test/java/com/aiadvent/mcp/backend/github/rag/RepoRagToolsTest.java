package com.aiadvent.mcp.backend.github.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aiadvent.mcp.backend.config.GitHubRagProperties;
import com.aiadvent.mcp.backend.github.GitHubRepositoryFetchRegistry;
import com.aiadvent.mcp.backend.github.rag.persistence.RepoRagNamespaceStateEntity;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
  @Mock private RepoRagToolInputSanitizer inputSanitizer;
  @Mock private GitHubRagProperties properties;
  @Mock private RagParameterGuard parameterGuard;
  @Mock private GraphQueryService graphQueryService;

  private RepoRagTools tools;
  private GitHubRagProperties.ResolvedRagParameterProfile balancedProfile;
  private RagParameterGuard.ResolvedSearchPlan balancedPlan;

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);
    tools =
        new RepoRagTools(
            statusService,
            searchService,
            namespaceStateService,
            fetchRegistry,
            inputSanitizer,
            properties,
            parameterGuard,
            graphQueryService);
    when(inputSanitizer.sanitizeSimple(any()))
        .thenAnswer(
            invocation ->
                new RepoRagToolInputSanitizer.SanitizationResult<>(
                    invocation.getArgument(0), List.of()));
    when(inputSanitizer.sanitizeGlobal(any()))
        .thenAnswer(
            invocation ->
                new RepoRagToolInputSanitizer.SanitizationResult<>(
                    invocation.getArgument(0), List.of()));
    balancedProfile = profile("balanced");
    balancedPlan = plan("balanced");
    when(properties.resolveProfile(any())).thenReturn(balancedProfile);
    when(parameterGuard.apply(any()))
        .thenReturn(new RagParameterGuard.GuardResult(balancedPlan, List.of()));
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
            List.of(),
            false,
            "prompt",
            "instructions",
            false,
            true,
            null,
            List.of(),
            List.of(),
            "summary",
            "raw");
    when(searchService.search(any())).thenReturn(response);

    RepoRagTools.RepoRagSearchResponse result =
        tools.ragSearchSimple(new RepoRagTools.RepoRagSimpleSearchInput("Что нового?"));

    assertThat(result.instructions()).isEqualTo("instructions");
    assertThat(result.warnings()).isEmpty();
    ArgumentCaptor<RepoRagSearchService.SearchCommand> captor =
        ArgumentCaptor.forClass(RepoRagSearchService.SearchCommand.class);
    verify(searchService).search(captor.capture());
    assertThat(captor.getValue().repoOwner()).isEqualTo("example");
    assertThat(captor.getValue().repoName()).isEqualTo("repo");
    assertThat(captor.getValue().plan().profileName()).isEqualTo("balanced");
  }

  @Test
  void ragSearchSimpleRequiresFetch() {
    when(fetchRegistry.latest()).thenReturn(Optional.empty());

    assertThatThrownBy(() -> tools.ragSearchSimple(new RepoRagTools.RepoRagSimpleSearchInput("q")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("github.repository_fetch");
  }

  @Test
  void ragSearchSimpleGlobalUsesGlobalSearch() {
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
            List.of(),
            false,
            "prompt",
            "instructions",
            false,
            false,
            null,
            List.of(),
            List.of(),
            "summary",
            "raw");
    when(searchService.searchGlobal(any())).thenReturn(response);

    RepoRagTools.RepoRagSearchResponse result =
        tools.ragSearchSimpleGlobal(new RepoRagTools.RepoRagSimpleGlobalSearchInput("Query"));

    assertThat(result.instructions()).isEqualTo("instructions");
    assertThat(result.warnings()).isEmpty();
    ArgumentCaptor<RepoRagSearchService.GlobalSearchCommand> captor =
        ArgumentCaptor.forClass(RepoRagSearchService.GlobalSearchCommand.class);
    verify(searchService).searchGlobal(captor.capture());
    assertThat(captor.getValue().displayRepoOwner()).isEqualTo("example");
    assertThat(captor.getValue().displayRepoName()).isEqualTo("repo");
  }

  @Test
  void ragSearchSimpleGlobalFallsBackWithoutFetch() {
    when(fetchRegistry.latest()).thenReturn(Optional.empty());
    RepoRagSearchService.SearchResponse response =
        new RepoRagSearchService.SearchResponse(
            List.of(),
            false,
            "prompt",
            "instructions",
            false,
            false,
            null,
            List.of(),
            List.of(),
            "summary",
            "raw");
    when(searchService.searchGlobal(any())).thenReturn(response);

    RepoRagTools.RepoRagSearchResponse result =
        tools.ragSearchSimpleGlobal(new RepoRagTools.RepoRagSimpleGlobalSearchInput("any"));

    assertThat(result.warnings()).isEmpty();
    ArgumentCaptor<RepoRagSearchService.GlobalSearchCommand> captor =
        ArgumentCaptor.forClass(RepoRagSearchService.GlobalSearchCommand.class);
    verify(searchService).searchGlobal(captor.capture());
    assertThat(captor.getValue().displayRepoOwner()).isEqualTo("global");
  }

  @Test
  void ragSearchGlobalUsesService() {
    RepoRagSearchService.SearchResponse response =
        new RepoRagSearchService.SearchResponse(
            List.of(),
            false,
            "prompt",
            "instructions",
            false,
            false,
            null,
            List.of(),
            List.of(),
            "summary",
            "raw");
    when(searchService.searchGlobal(any())).thenReturn(response);

    RepoRagTools.RepoRagGlobalSearchInput input =
        new RepoRagTools.RepoRagGlobalSearchInput(
            "How to build?",
            "balanced",
            List.of(),
            null,
            "global-owner",
            "mixed",
            "both");

    when(inputSanitizer.sanitizeGlobal(any()))
        .thenReturn(new RepoRagToolInputSanitizer.SanitizationResult<>(input, List.of("autofix")));

    RepoRagTools.RepoRagSearchResponse result = tools.ragSearchGlobal(input);
    assertThat(result.instructions()).isEqualTo("instructions");
    assertThat(result.warnings()).containsExactly("autofix");
    ArgumentCaptor<RepoRagSearchService.GlobalSearchCommand> captor =
        ArgumentCaptor.forClass(RepoRagSearchService.GlobalSearchCommand.class);
    verify(searchService).searchGlobal(captor.capture());
    assertThat(captor.getValue().displayRepoOwner()).isEqualTo("global-owner");
    assertThat(captor.getValue().plan().profileName()).isEqualTo("balanced");
  }

  private GitHubRagProperties.ResolvedRagParameterProfile profile(String name) {
    return new GitHubRagProperties.ResolvedRagParameterProfile(
        name,
        8,
        8,
        0.55d,
        Map.of(),
        8,
        true,
        2.0d,
        new GitHubRagProperties.ResolvedRagParameterProfile.ResolvedMultiQuery(true, 3, 3),
        new GitHubRagProperties.ResolvedRagParameterProfile.ResolvedNeighbor("LINEAR", 1, 6),
        null,
        null,
        List.of());
  }

  private RagParameterGuard.ResolvedSearchPlan plan(String name) {
    return new RagParameterGuard.ResolvedSearchPlan(
        name,
        8,
        8,
        0.55d,
        Map.of(),
        8,
        true,
        2.0d,
        new RepoRagMultiQueryOptions(true, 3, 3),
        new RagParameterGuard.NeighborOptions("LINEAR", 1, 6),
        RagParameterGuard.SearchPlanMode.STANDARD,
        null,
        null,
        List.of());
  }
}
