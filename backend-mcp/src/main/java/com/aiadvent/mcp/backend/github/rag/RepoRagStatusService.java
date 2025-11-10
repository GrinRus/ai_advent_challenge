package com.aiadvent.mcp.backend.github.rag;

import com.aiadvent.mcp.backend.github.rag.persistence.RepoRagIndexJobEntity;
import com.aiadvent.mcp.backend.github.rag.persistence.RepoRagIndexJobRepository;
import com.aiadvent.mcp.backend.github.rag.persistence.RepoRagJobStatus;
import com.aiadvent.mcp.backend.github.rag.persistence.RepoRagNamespaceStateEntity;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class RepoRagStatusService {

  private final RepoRagIndexJobRepository jobRepository;
  private final RepoRagNamespaceStateService namespaceStateService;

  public RepoRagStatusService(
      RepoRagIndexJobRepository jobRepository,
      RepoRagNamespaceStateService namespaceStateService) {
    this.jobRepository = jobRepository;
    this.namespaceStateService = namespaceStateService;
  }

  public StatusView currentStatus(String repoOwner, String repoName) {
    if (!StringUtils.hasText(repoOwner) || !StringUtils.hasText(repoName)) {
      throw new IllegalArgumentException("repoOwner and repoName must be provided");
    }

    Optional<RepoRagIndexJobEntity> optionalJob =
        jobRepository.findFirstByRepoOwnerIgnoreCaseAndRepoNameIgnoreCaseOrderByQueuedAtDesc(
            normalize(repoOwner), normalize(repoName));

    Optional<RepoRagNamespaceStateEntity> namespaceState =
        namespaceStateService.findByRepoOwnerAndRepoName(
            normalize(repoOwner), normalize(repoName));

    if (optionalJob.isPresent()) {
      RepoRagIndexJobEntity job = optionalJob.get();
      Progress progress = computeProgress(job);
      RepoRagNamespaceStateEntity state = namespaceState.orElse(null);

      return new StatusView(
          job.getRepoOwner(),
          job.getRepoName(),
          job.getStatus().name(),
          job.getAttempt(),
          job.getMaxAttempts(),
          progress.percentage(),
          progress.etaSeconds(),
          job.getFilesProcessed(),
          job.getChunksProcessed(),
          job.getQueuedAt(),
          job.getStartedAt(),
          job.getCompletedAt(),
          job.getLastError() != null ? job.getLastError().path("message").asText(null) : null,
          job.getFilesSkipped(),
          state != null ? state.getSourceRef() : null,
          state != null ? state.getCommitSha() : null,
          state != null ? state.getWorkspaceId() : null,
          state != null && state.isReady());
    }

    if (namespaceState.isPresent()) {
      RepoRagNamespaceStateEntity state = namespaceState.get();
      String status = state.isReady() ? "READY" : "STALE";
      return new StatusView(
          state.getRepoOwner(),
          state.getRepoName(),
          status,
          0,
          0,
          state.isReady() ? 1.0 : 0.0,
          null,
          state.getFilesTotal() - state.getFilesSkipped(),
          state.getChunksTotal(),
          null,
          null,
          state.getLastIndexedAt(),
          null,
          state.getFilesSkipped(),
          state.getSourceRef(),
          state.getCommitSha(),
          state.getWorkspaceId(),
          state.isReady());
    }

    return StatusView.notFound(normalize(repoOwner), normalize(repoName));
  }

  private Progress computeProgress(RepoRagIndexJobEntity job) {
    double processed = Math.max(1, job.getChunksProcessed());
    double total = Math.max(processed, job.getChunksTotal());
    double percentage = Math.min(1.0, processed / total);

    Long etaSeconds = null;
    if (job.getStatus() == RepoRagJobStatus.RUNNING
        && job.getStartedAt() != null
        && job.getChunksProcessed() > 0) {
      Duration elapsed = Duration.between(job.getStartedAt(), Instant.now());
      double rate = job.getChunksProcessed() / Math.max(1, elapsed.getSeconds());
      if (rate > 0) {
        long remaining = Math.max(0, job.getChunksTotal() - job.getChunksProcessed());
        etaSeconds = Math.round(remaining / rate);
      }
    }

    return new Progress(percentage, etaSeconds);
  }

  private String normalize(String value) {
    return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
  }

  private record Progress(double percentage, Long etaSeconds) {}

  public record StatusView(
      String repoOwner,
      String repoName,
      String status,
      int attempt,
      int maxAttempts,
      double progress,
      Long etaSeconds,
      long filesProcessed,
      long chunksProcessed,
      Instant queuedAt,
      Instant startedAt,
      Instant completedAt,
      String lastError,
      long filesSkipped,
      String sourceRef,
      String commitSha,
      String workspaceId,
      boolean ready) {

    static StatusView notFound(String owner, String name) {
      return new StatusView(
          owner,
          name,
          "NOT_FOUND",
          0,
          0,
          0.0,
          null,
          0,
          0,
          null,
          null,
          null,
          null,
          0,
          null,
          null,
          null,
          false);
    }
  }
}
