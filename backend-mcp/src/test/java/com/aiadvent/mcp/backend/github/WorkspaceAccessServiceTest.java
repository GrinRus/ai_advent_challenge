package com.aiadvent.mcp.backend.github;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.aiadvent.mcp.backend.github.GitHubRepositoryService.CheckoutStrategy;
import com.aiadvent.mcp.backend.github.GitHubRepositoryService.GitFetchOptions;
import com.aiadvent.mcp.backend.github.GitHubRepositoryService.RepositoryRef;
import com.aiadvent.mcp.backend.github.workspace.WorkspaceInspectorService;
import com.aiadvent.mcp.backend.workspace.WorkspaceFileService;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WorkspaceAccessServiceTest {

  @Mock private GitHubRepositoryService repositoryService;
  @Mock private WorkspaceInspectorService inspectorService;
  @Mock private WorkspaceFileService workspaceFileService;

  private WorkspaceAccessService accessService;

  @BeforeEach
  void setUp() {
    accessService = new WorkspaceAccessService(repositoryService, inspectorService, workspaceFileService);
  }

  @Test
  void fetchRepositoryPropagatesRepositoryNotFound() {
    GitFetchOptions options =
        new GitFetchOptions(
            CheckoutStrategy.ARCHIVE_ONLY,
            true,
            false,
            Duration.ofSeconds(30),
            Duration.ofSeconds(30),
            10_000_000L,
            true);
    RepositoryRef repositoryRef = new RepositoryRef("sandbox-co", "demo-service", "refs/heads/main");

    when(repositoryService.fetchRepository(any())).thenThrow(new IllegalArgumentException("repository not found"));

    assertThatThrownBy(() -> accessService.fetchRepository(options, repositoryRef, "req-404"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("repository not found");
  }

  @Test
  void fetchRepositoryPropagatesArchiveLimitErrors() {
    GitFetchOptions options =
        new GitFetchOptions(
            CheckoutStrategy.ARCHIVE_ONLY,
            true,
            false,
            Duration.ofSeconds(30),
            Duration.ofSeconds(30),
            1_000_000L,
            true);
    RepositoryRef repositoryRef = new RepositoryRef("sandbox-co", "demo-service", "refs/heads/main");

    when(repositoryService.fetchRepository(any()))
        .thenThrow(new IllegalStateException("archive exceeds limit"));

    assertThatThrownBy(() -> accessService.fetchRepository(options, repositoryRef, "req-limit"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("archive exceeds limit");
  }
}
