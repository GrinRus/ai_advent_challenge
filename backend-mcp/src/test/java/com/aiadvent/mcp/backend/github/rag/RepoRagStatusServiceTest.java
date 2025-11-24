package com.aiadvent.mcp.backend.github.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.aiadvent.mcp.backend.config.GitHubRagProperties;
import com.aiadvent.mcp.backend.github.rag.persistence.RepoRagIndexJobEntity;
import com.aiadvent.mcp.backend.github.rag.persistence.RepoRagIndexJobRepository;
import com.aiadvent.mcp.backend.github.rag.persistence.RepoRagJobStatus;
import com.aiadvent.mcp.backend.github.rag.persistence.RepoRagNamespaceStateEntity;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RepoRagStatusServiceTest {

  @Mock private RepoRagIndexJobRepository jobRepository;
  @Mock private RepoRagNamespaceStateService namespaceStateService;

  private GitHubRagProperties properties;
  private RepoRagStatusService service;

  @BeforeEach
  void setUp() {
    properties = new GitHubRagProperties();
    properties.getGraph().setEnabled(false);
    service = new RepoRagStatusService(jobRepository, namespaceStateService, properties);
  }

  @Test
  void returnsProgressForRunningJob() {
    RepoRagIndexJobEntity job = new RepoRagIndexJobEntity();
    job.setRepoOwner("owner");
    job.setRepoName("repo");
    job.setStatus(RepoRagJobStatus.RUNNING);
    job.setChunksProcessed(5);
    job.setChunksTotal(10);
    job.setStartedAt(Instant.now().minusSeconds(5));
    job.setAttempt(2);
    job.setMaxAttempts(5);
    when(jobRepository.findFirstByRepoOwnerIgnoreCaseAndRepoNameIgnoreCaseOrderByQueuedAtDesc(
            "owner", "repo"))
        .thenReturn(Optional.of(job));
    when(namespaceStateService.findByRepoOwnerAndRepoName("owner", "repo"))
        .thenReturn(Optional.empty());

    RepoRagStatusService.StatusView status = service.currentStatus("Owner", "Repo");

    assertThat(status.repoOwner()).isEqualTo("owner");
    assertThat(status.progress()).isBetween(0.49, 0.51);
    assertThat(status.status()).isEqualTo("RUNNING");
    assertThat(status.etaSeconds()).isNotNull();
  }

  @Test
  void returnsNotFoundIfNoJob() {
    when(jobRepository.findFirstByRepoOwnerIgnoreCaseAndRepoNameIgnoreCaseOrderByQueuedAtDesc(
            "owner", "repo"))
        .thenReturn(Optional.empty());
    when(namespaceStateService.findByRepoOwnerAndRepoName("owner", "repo"))
        .thenReturn(Optional.empty());

    RepoRagStatusService.StatusView status = service.currentStatus("owner", "repo");

    assertThat(status.status()).isEqualTo("NOT_FOUND");
    assertThat(status.filesProcessed()).isZero();
  }

  @Test
  void returnsReadyStateWhenNoActiveJob() {
    when(jobRepository.findFirstByRepoOwnerIgnoreCaseAndRepoNameIgnoreCaseOrderByQueuedAtDesc(
            "owner", "repo"))
        .thenReturn(Optional.empty());
    RepoRagNamespaceStateEntity state = new RepoRagNamespaceStateEntity();
    state.setNamespace("repo:owner/repo");
    state.setRepoOwner("owner");
    state.setRepoName("repo");
    state.setReady(true);
    state.setFilesTotal(10);
    when(namespaceStateService.findByRepoOwnerAndRepoName("owner", "repo"))
        .thenReturn(Optional.of(state));

    RepoRagStatusService.StatusView status = service.currentStatus("owner", "repo");

    assertThat(status.status()).isEqualTo("READY");
    assertThat(status.ready()).isTrue();
  }

  @Test
  void exposesGraphFieldsFromNamespaceState() {
    properties.getGraph().setEnabled(true);
    RepoRagNamespaceStateEntity state = new RepoRagNamespaceStateEntity();
    state.setNamespace("repo:owner/repo");
    state.setRepoOwner("owner");
    state.setRepoName("repo");
    state.setReady(true);
    state.setGraphReady(false);
    state.setGraphSchemaVersion(2);
    Instant graphReadyAt = Instant.now();
    state.setGraphReadyAt(graphReadyAt);
    state.setGraphSyncError("Neo4j unavailable");
    when(jobRepository.findFirstByRepoOwnerIgnoreCaseAndRepoNameIgnoreCaseOrderByQueuedAtDesc(
            "owner", "repo"))
        .thenReturn(Optional.empty());
    when(namespaceStateService.findByRepoOwnerAndRepoName("owner", "repo"))
        .thenReturn(Optional.of(state));

    RepoRagStatusService.StatusView status = service.currentStatus("owner", "repo");

    assertThat(status.graphSchemaVersion()).isEqualTo(2);
    assertThat(status.graphReadyAt()).isEqualTo(graphReadyAt);
    assertThat(status.graphReady()).isFalse();
    assertThat(status.graphSyncError()).isEqualTo("Neo4j unavailable");
  }
}
