package com.aiadvent.mcp.backend.github.rag;

import com.aiadvent.mcp.backend.config.GitHubRagProperties;
import com.aiadvent.mcp.backend.github.rag.persistence.RepoRagIndexJobEntity;
import com.aiadvent.mcp.backend.github.rag.persistence.RepoRagIndexJobRepository;
import com.aiadvent.mcp.backend.github.rag.persistence.RepoRagJobStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class RepoRagIndexScheduler {

  private static final Logger log = LoggerFactory.getLogger(RepoRagIndexScheduler.class);

  private final RepoRagIndexJobRepository jobRepository;
  private final RepoRagIndexService indexService;
  private final RepoRagNamespaceStateService namespaceStateService;
  private final GitHubRagProperties properties;
  private final ObjectMapper objectMapper;
  private final ScheduledExecutorService executor;
  private final Map<UUID, RepoRagWorkRequest> workRequests = new ConcurrentHashMap<>();
  private final AtomicInteger queued = new AtomicInteger();
  private final Timer indexDuration;
  private final Counter indexFailures;
  private final Counter embeddingsTotal;

  public RepoRagIndexScheduler(
      RepoRagIndexJobRepository jobRepository,
      RepoRagIndexService indexService,
      RepoRagNamespaceStateService namespaceStateService,
      GitHubRagProperties properties,
      ObjectMapper objectMapper,
      @Nullable MeterRegistry meterRegistry) {
    this.jobRepository = jobRepository;
    this.indexService = indexService;
    this.namespaceStateService = namespaceStateService;
    this.properties = properties;
    this.objectMapper = objectMapper;
    int poolSize = Math.max(1, properties.getMaxConcurrency());
    this.executor = new ScheduledThreadPoolExecutor(poolSize, new WorkerFactory());
    MeterRegistry registry = meterRegistry;
    if (registry == null) {
      registry = new SimpleMeterRegistry();
    }
    Gauge.builder("repo_rag_queue_depth", queued, AtomicInteger::get).register(registry);
    this.indexDuration = registry.timer("repo_rag_index_duration");
    this.indexFailures = registry.counter("repo_rag_index_fail_total");
    this.embeddingsTotal = registry.counter("repo_rag_embeddings_total");
  }

  public void scheduleIndexing(
      String repoOwner,
      String repoName,
      String workspaceId,
      String sourceRef,
      String commitSha,
      long workspaceSizeBytes,
      Instant fetchedAt) {
    if (!StringUtils.hasText(repoOwner) || !StringUtils.hasText(repoName)) {
      return;
    }
    RepoRagIndexJobEntity job = new RepoRagIndexJobEntity();
    job.setRepoOwner(normalize(repoOwner));
    job.setRepoName(normalize(repoName));
    job.setStatus(RepoRagJobStatus.QUEUED);
    job.setMaxAttempts(properties.getRetry().getMaxAttempts());
    job.setQueuedAt(Instant.now());
    jobRepository.save(job);

    RepoRagWorkRequest request =
        new RepoRagWorkRequest(
            job.getId(),
            buildNamespace(repoOwner, repoName),
            normalize(repoOwner),
            normalize(repoName),
            workspaceId,
            sourceRef,
            commitSha,
            workspaceSizeBytes,
            fetchedAt);
    namespaceStateService.markPending(
        request.namespace(),
        request.repoOwner(),
        request.repoName(),
        request.sourceRef(),
        request.commitSha(),
        request.workspaceId(),
        request.fetchedAt(),
        job,
        request.workspaceSizeBytes());
    workRequests.put(job.getId(), request);
    queued.incrementAndGet();
    scheduleExecution(request, Duration.ZERO);
    log.info(
        "Queued repo RAG indexing job {} for {}/{} (workspaceId={})",
        job.getId(),
        repoOwner,
        repoName,
        workspaceId);
  }

  private void scheduleExecution(RepoRagWorkRequest request, Duration delay) {
    long delayMs = Math.max(0, delay.toMillis());
    executor.schedule(() -> runJob(request), delayMs, TimeUnit.MILLISECONDS);
  }

  private void runJob(RepoRagWorkRequest request) {
    queued.updateAndGet(value -> Math.max(0, value - 1));
    Optional<RepoRagIndexJobEntity> optionalJob = jobRepository.findById(request.jobId());
    if (optionalJob.isEmpty()) {
      workRequests.remove(request.jobId());
      return;
    }

    RepoRagIndexJobEntity job = optionalJob.get();
    try {
      job.setStatus(RepoRagJobStatus.RUNNING);
      job.setStartedAt(Instant.now());
      job.setAttempt(job.getAttempt() + 1);
      jobRepository.save(job);

      RepoRagIndexService.IndexRequest indexRequest =
          new RepoRagIndexService.IndexRequest(
              request.repoOwner(),
              request.repoName(),
              request.workspaceId(),
              request.namespace(),
              request.sourceRef(),
              request.commitSha(),
              request.workspaceSizeBytes(),
              request.fetchedAt());

      boolean graphSyncEnabled = indexService.isGraphSyncEnabled();
      if (graphSyncEnabled) {
        namespaceStateService.markGraphSyncStarted(
            request.namespace(), RepoRagIndexService.AST_VERSION);
      }

      Instant started = Instant.now();
      RepoRagIndexService.IndexResult result = indexService.indexWorkspace(indexRequest);
      Duration duration = Duration.between(started, Instant.now());

      job.setStatus(RepoRagJobStatus.SUCCEEDED);
      job.setFilesProcessed(result.filesProcessed());
      job.setChunksProcessed(result.chunksProcessed());
      job.setChunksTotal(result.chunksProcessed());
      long filesTotal = result.filesProcessed() + result.filesSkipped() + result.filesDeleted();
      job.setFilesTotal(filesTotal);
      job.setCompletedAt(Instant.now());
      job.setFilesSkipped(result.filesSkipped());
      job.setLastError(null);
      jobRepository.save(job);
      namespaceStateService.markReady(
          request.namespace(),
          request.repoOwner(),
          request.repoName(),
          request.sourceRef(),
          request.commitSha(),
          request.workspaceId(),
          request.fetchedAt(),
          job,
          filesTotal,
          result.chunksProcessed(),
          result.filesSkipped(),
          request.workspaceSizeBytes(),
          result.astReady(),
          result.astReady() ? RepoRagIndexService.AST_VERSION : 0);

      RepoRagIndexService.GraphSyncResult graphSync = result.graphSync();
      if (graphSync.enabled()) {
        if (graphSync.shouldMarkReady()) {
          namespaceStateService.markGraphSyncSucceeded(
              request.namespace(), RepoRagIndexService.AST_VERSION);
        } else {
          String error = graphSync.errorMessage();
          if (!StringUtils.hasText(error)) {
            error = graphSync.attempted()
                ? "Graph sync failed"
                : "Graph sync was not attempted";
          }
          namespaceStateService.markGraphSyncFailed(request.namespace(), error);
        }
      } else if (graphSyncEnabled) {
        namespaceStateService.markGraphSyncFailed(
            request.namespace(), "Graph sync disabled in runtime");
      }

      embeddingsTotal.increment(result.chunksProcessed());
      indexDuration.record(duration);
      workRequests.remove(request.jobId());

      if (!result.warnings().isEmpty()) {
        log.info(
            "Repo RAG job {} completed with warnings: {}", job.getId(), result.warnings());
      }
    } catch (Exception ex) {
      handleFailure(request, job, ex);
    }
  }

  private void handleFailure(RepoRagWorkRequest request, RepoRagIndexJobEntity job, Exception ex) {
    indexFailures.increment();
    job.setStatus(RepoRagJobStatus.FAILED);
    job.setLastError(buildErrorPayload(ex));
    job.setCompletedAt(Instant.now());
    jobRepository.save(job);

    if (indexService.isGraphSyncEnabled()) {
      namespaceStateService.markGraphSyncFailed(
          request.namespace(), "Graph sync aborted: " + ex.getMessage());
    }

    boolean retryable = isRetryable(ex) && job.getAttempt() < job.getMaxAttempts();
    if (retryable) {
      Duration initial = properties.getRetry().getInitialBackoff();
      int attempt = job.getAttempt();
      Duration delay = initial.multipliedBy((long) Math.pow(2, Math.max(0, attempt - 1)));
      log.warn(
          "Repo RAG job {} failed (attempt {}/{}). Retrying in {}. Error: {}",
          job.getId(),
          attempt,
          job.getMaxAttempts(),
          delay,
          ex.getMessage());
      queued.incrementAndGet();
      scheduleExecution(request, delay);
    } else {
      workRequests.remove(request.jobId());
      log.error(
          "Repo RAG job {} failed permanently after {} attempts: {}",
          job.getId(),
          job.getAttempt(),
          ex.getMessage());
      namespaceStateService.markFailed(request.namespace(), job);
    }
  }

  private boolean isRetryable(Exception ex) {
    if (ex instanceof IllegalArgumentException argumentException) {
      return !argumentException.getMessage().contains("Unknown workspaceId");
    }
    return true;
  }

  private ObjectNode buildErrorPayload(Exception ex) {
    ObjectNode node = objectMapper.createObjectNode();
    node.put("message", ex.getMessage());
    node.put("type", ex.getClass().getSimpleName());
    return node;
  }

  private String buildNamespace(String owner, String name) {
    return properties.getNamespacePrefix() + ":" + normalize(owner) + "/" + normalize(name);
  }

  private String normalize(String value) {
    return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
  }

  @PreDestroy
  public void shutdown() {
    executor.shutdown();
  }

  private static class WorkerFactory implements ThreadFactory {
    private final AtomicInteger counter = new AtomicInteger();

    @Override
    public Thread newThread(Runnable r) {
      Thread thread = new Thread(r);
      thread.setName("repo-rag-indexer-" + counter.incrementAndGet());
      thread.setDaemon(true);
      return thread;
    }
  }

  private record RepoRagWorkRequest(
      UUID jobId,
      String namespace,
      String repoOwner,
      String repoName,
      String workspaceId,
      String sourceRef,
      String commitSha,
      long workspaceSizeBytes,
      Instant fetchedAt) {}
}
