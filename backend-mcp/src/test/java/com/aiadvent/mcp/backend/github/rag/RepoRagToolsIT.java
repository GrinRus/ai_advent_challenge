package com.aiadvent.mcp.backend.github.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.aiadvent.mcp.backend.config.GitHubRagProperties;
import com.aiadvent.mcp.backend.github.GitHubRepositoryFetchRegistry;
import com.aiadvent.mcp.backend.github.rag.persistence.RepoRagNamespaceStateEntity;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(classes = RepoRagToolsIT.TestConfig.class)
@ActiveProfiles("test")
class RepoRagToolsIT {

  @Autowired private RepoRagTools tools;
  @Autowired private RepoRagSearchService searchService;
  @Autowired private RepoRagNamespaceStateService namespaceStateService;
  @Autowired private GitHubRepositoryFetchRegistry fetchRegistry;

  @BeforeEach
  void resetMocks() {
    org.mockito.Mockito.reset(searchService, namespaceStateService, fetchRegistry);
  }

  @Test
  void ragSearchUsesProfilePlanAndAppendsModule() {
    RepoRagNamespaceStateEntity state = new RepoRagNamespaceStateEntity();
    state.setNamespace("repo:owner/repo");
    state.setRepoOwner("owner");
    state.setRepoName("repo");
    state.setReady(true);
    when(namespaceStateService.findByRepoOwnerAndRepoName("owner", "repo"))
        .thenReturn(Optional.of(state));

    RepoRagSearchService.SearchResponse response =
        new RepoRagSearchService.SearchResponse(
            List.of(),
            false,
            "prompt",
            "instr",
            false,
            false,
            null,
            List.of("retrieval"),
            List.of(),
            "summary",
            "raw");
    when(searchService.search(any())).thenReturn(response);

    RepoRagTools.RepoRagSearchInput input =
        new RepoRagTools.RepoRagSearchInput(
            "owner", "repo", "Explain", "aggressive", List.of(), null, "both");

    RepoRagTools.RepoRagSearchResponse result = tools.ragSearch(input);

    ArgumentCaptor<RepoRagSearchService.SearchCommand> captor =
        ArgumentCaptor.forClass(RepoRagSearchService.SearchCommand.class);
    org.mockito.Mockito.verify(searchService).search(captor.capture());
    RagParameterGuard.ResolvedSearchPlan plan = captor.getValue().plan();
    assertThat(plan.profileName()).isEqualTo("aggressive");
    assertThat(plan.topK()).isEqualTo(14);
    assertThat(plan.multiQuery().queries()).isEqualTo(4);
    assertThat(result.appliedModules()).contains("profile:aggressive");
  }

  @Test
  void ragSearchSimpleUsesLatestFetchAndDefaultProfile() {
    when(fetchRegistry.latest()).thenReturn(Optional.of(fetchContext("owner", "repo")));
    when(namespaceStateService.findByRepoOwnerAndRepoName("owner", "repo"))
        .thenReturn(Optional.of(readyState("owner", "repo")));
    RepoRagSearchService.SearchResponse response =
        new RepoRagSearchService.SearchResponse(
            List.of(),
            false,
            "prompt",
            "instr",
            false,
            false,
            null,
            List.of("retrieval"),
            List.of(),
            "summary",
            "raw");
    when(searchService.search(any())).thenReturn(response);

    RepoRagTools.RepoRagSearchResponse result =
        tools.ragSearchSimple(new RepoRagTools.RepoRagSimpleSearchInput("simple"));

    ArgumentCaptor<RepoRagSearchService.SearchCommand> captor =
        ArgumentCaptor.forClass(RepoRagSearchService.SearchCommand.class);
    org.mockito.Mockito.verify(searchService).search(captor.capture());
    assertThat(captor.getValue().plan().profileName()).isEqualTo("balanced");
    assertThat(result.appliedModules()).contains("profile:balanced");
  }

  @Test
  void simpleGlobalFallsBackToDefaultProfile() {
    RepoRagSearchService.SearchResponse response =
        new RepoRagSearchService.SearchResponse(
            List.of(),
            false,
            "prompt",
            "instr",
            false,
            false,
            null,
            List.of(),
            List.of(),
            "summary",
            "raw");
    when(searchService.searchGlobal(any())).thenReturn(response);
    when(fetchRegistry.latest()).thenReturn(Optional.empty());

    RepoRagTools.RepoRagSearchResponse result =
        tools.ragSearchSimpleGlobal(new RepoRagTools.RepoRagSimpleGlobalSearchInput("Any"));

    ArgumentCaptor<RepoRagSearchService.GlobalSearchCommand> captor =
        ArgumentCaptor.forClass(RepoRagSearchService.GlobalSearchCommand.class);
    org.mockito.Mockito.verify(searchService).searchGlobal(captor.capture());
    assertThat(captor.getValue().plan().profileName()).isEqualTo("balanced");
    assertThat(result.appliedModules()).contains("profile:balanced");
  }

  private RepoRagNamespaceStateEntity readyState(String owner, String repo) {
    RepoRagNamespaceStateEntity entity = new RepoRagNamespaceStateEntity();
    entity.setNamespace("repo:" + owner + "/" + repo);
    entity.setRepoOwner(owner);
    entity.setRepoName(repo);
    entity.setReady(true);
    return entity;
  }

  private GitHubRepositoryFetchRegistry.LastFetchContext fetchContext(String owner, String repo) {
    return new GitHubRepositoryFetchRegistry.LastFetchContext(
        owner, repo, "refs/heads/main", "sha", "ws", java.time.Instant.now());
  }

  @TestConfiguration
  @Profile("test")
  @Import({RepoRagTools.class, RepoRagToolInputSanitizer.class, RagParameterGuard.class})
  static class TestConfig {

    @Bean
    GitHubRagProperties gitHubRagProperties() {
      GitHubRagProperties properties = new GitHubRagProperties();
      properties.setParameterProfiles(
          List.of(profile("balanced", 10, 3, "LINEAR", 1, 6), profile("aggressive", 14, 4, "CALL_GRAPH", 2, 10)));
      properties.setDefaultProfile("balanced");
      try {
        properties.afterPropertiesSet();
      } catch (Exception ex) {
        throw new IllegalStateException(ex);
      }
      return properties;
    }

    @Bean
    MeterRegistry meterRegistry() {
      return new SimpleMeterRegistry();
    }

    @Bean
    RepoRagSearchService searchService() {
      return org.mockito.Mockito.mock(RepoRagSearchService.class);
    }

    @Bean
    RepoRagNamespaceStateService namespaceStateService() {
      return org.mockito.Mockito.mock(RepoRagNamespaceStateService.class);
    }

    @Bean
    GitHubRepositoryFetchRegistry fetchRegistry() {
      return org.mockito.Mockito.mock(GitHubRepositoryFetchRegistry.class);
    }

    @Bean
    RepoRagStatusService statusService() {
      return org.mockito.Mockito.mock(RepoRagStatusService.class);
    }

    private static GitHubRagProperties.RagParameterProfile profile(
        String name, int topK, int multiQuery, String neighborStrategy, int radius, int limit) {
      GitHubRagProperties.RagParameterProfile profile = new GitHubRagProperties.RagParameterProfile();
      profile.setName(name);
      profile.setTopK(topK);
      profile.setTopKPerQuery(topK);
      profile.setMinScore(0.55d);
      configureMultiQuery(profile, multiQuery);
      configureNeighbor(profile, neighborStrategy, radius, limit);
      profile.setCodeAwareEnabled(true);
      profile.setCodeAwareHeadMultiplier(2.0d);
      profile.setRerankTopN(8);
      return profile;
    }

    private static void configureMultiQuery(
        GitHubRagProperties.RagParameterProfile profile, int queries) {
      GitHubRagProperties.RagParameterProfile.ProfileMultiQuery multiQuery =
          profile.getMultiQuery();
      multiQuery.setEnabled(true);
      multiQuery.setQueries(queries);
      multiQuery.setMaxQueries(queries);
    }

    private static void configureNeighbor(
        GitHubRagProperties.RagParameterProfile profile,
        String strategy,
        int radius,
        int limit) {
      GitHubRagProperties.RagParameterProfile.ProfileNeighbor neighbor = profile.getNeighbor();
      neighbor.setStrategy(strategy);
      neighbor.setRadius(radius);
      neighbor.setLimit(limit);
    }
  }
}
