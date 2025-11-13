package com.aiadvent.mcp.backend.github.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class RepoRagToolInputSanitizerTest {

  @Mock private GitHubRagProperties properties;
  @Mock private GitHubRepositoryFetchRegistry fetchRegistry;
  @Mock private RepoRagNamespaceStateService namespaceStateService;

  private RepoRagToolInputSanitizer sanitizer;
  private GitHubRagProperties.ResolvedRagParameterProfile balancedProfile;

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);
    sanitizer =
        new RepoRagToolInputSanitizer(properties, fetchRegistry, namespaceStateService, null);
    balancedProfile = profile("balanced");
    when(properties.resolveProfile(null)).thenReturn(balancedProfile);
    when(properties.resolveProfile("balanced")).thenReturn(balancedProfile);
  }

  @Test
  void sanitizeSearchAutoFillsOwnerAndProfile() {
    GitHubRepositoryFetchRegistry.LastFetchContext context =
        new GitHubRepositoryFetchRegistry.LastFetchContext(
            "Org", "Repo", "ref", "sha", "ws", Instant.now());
    RepoRagNamespaceStateEntity state = new RepoRagNamespaceStateEntity();
    state.setRepoOwner("org");
    state.setRepoName("repo");
    state.setReady(true);
    when(fetchRegistry.latest()).thenReturn(Optional.of(context));
    when(namespaceStateService.findByRepoOwnerAndRepoName("org", "repo"))
        .thenReturn(Optional.of(state));

    RepoRagTools.RepoRagSearchInput input =
        new RepoRagTools.RepoRagSearchInput(null, null, "  explain plan  ", null, List.of(), null);

    RepoRagToolInputSanitizer.SanitizationResult<RepoRagTools.RepoRagSearchInput> result =
        sanitizer.sanitizeSearch(input);

    RepoRagTools.RepoRagSearchInput sanitized = result.value();
    assertThat(sanitized.repoOwner()).isEqualTo("org");
    assertThat(sanitized.repoName()).isEqualTo("repo");
    assertThat(sanitized.rawQuery()).isEqualTo("explain plan");
    assertThat(sanitized.profile()).isEqualTo("balanced");
    assertThat(result.warnings())
        .containsExactly(
            "repoOwner заполнен автоматически значением org/repo",
            "repoName заполнен автоматически значением org/repo",
            "profile не указан — использован balanced");
  }

  @Test
  void sanitizeSearchFailsWhenNoReadyNamespace() {
    when(fetchRegistry.latest()).thenReturn(Optional.empty());
    RepoRagTools.RepoRagSearchInput input =
        new RepoRagTools.RepoRagSearchInput(null, null, "question", null, List.of(), null);

    assertThatThrownBy(() -> sanitizer.sanitizeSearch(input))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("github.repository_fetch");
  }

  @Test
  void sanitizeGlobalKeepsDisplayAndNormalizesProfile() {
    GitHubRagProperties.ResolvedRagParameterProfile aggressive = profile("aggressive");
    when(properties.resolveProfile("AGGRESSIVE")).thenReturn(aggressive);

    RepoRagTools.RepoRagGlobalSearchInput input =
        new RepoRagTools.RepoRagGlobalSearchInput(
            " Что такое MCP? ",
            "AGGRESSIVE",
            List.of(),
            null,
            "Mixed",
            "Catalog");

    RepoRagToolInputSanitizer.SanitizationResult<RepoRagTools.RepoRagGlobalSearchInput> result =
        sanitizer.sanitizeGlobal(input);

    RepoRagTools.RepoRagGlobalSearchInput sanitized = result.value();
    assertThat(sanitized.rawQuery()).isEqualTo("Что такое MCP?");
    assertThat(sanitized.profile()).isEqualTo("aggressive");
    assertThat(sanitized.displayRepoOwner()).isEqualTo("Mixed");
    assertThat(sanitized.displayRepoName()).isEqualTo("Catalog");
  }

  @Test
  void sanitizeGlobalFallsBackToGlobalLabels() {
    when(fetchRegistry.latest()).thenReturn(Optional.empty());
    RepoRagTools.RepoRagGlobalSearchInput input =
        new RepoRagTools.RepoRagGlobalSearchInput("test", null, List.of(), null, null, null);

    RepoRagToolInputSanitizer.SanitizationResult<RepoRagTools.RepoRagGlobalSearchInput> result =
        sanitizer.sanitizeGlobal(input);

    assertThat(result.value().displayRepoOwner()).isEqualTo("global");
    assertThat(result.value().displayRepoName()).isEqualTo("global");
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
        new GitHubRagProperties.ResolvedRagParameterProfile.ResolvedMultiQuery(true, 3, 4),
        new GitHubRagProperties.ResolvedRagParameterProfile.ResolvedNeighbor("LINEAR", 1, 6));
  }
}
