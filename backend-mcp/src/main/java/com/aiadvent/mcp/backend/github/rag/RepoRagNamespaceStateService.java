package com.aiadvent.mcp.backend.github.rag;

import com.aiadvent.mcp.backend.github.rag.persistence.RepoRagIndexJobEntity;
import com.aiadvent.mcp.backend.github.rag.persistence.RepoRagNamespaceStateEntity;
import com.aiadvent.mcp.backend.github.rag.persistence.RepoRagNamespaceStateRepository;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class RepoRagNamespaceStateService {

  private final RepoRagNamespaceStateRepository repository;

  public RepoRagNamespaceStateService(RepoRagNamespaceStateRepository repository) {
    this.repository = repository;
  }

  @Transactional
  public void markPending(
      String namespace,
      String repoOwner,
      String repoName,
      String sourceRef,
      String commitSha,
      String workspaceId,
      Instant fetchedAt,
      RepoRagIndexJobEntity job,
      long workspaceSizeBytes) {
    RepoRagNamespaceStateEntity entity =
        repository.findByNamespace(namespace).orElseGet(RepoRagNamespaceStateEntity::new);
    entity.setNamespace(namespace);
    entity.setRepoOwner(normalize(repoOwner));
    entity.setRepoName(normalize(repoName));
    entity.setSourceRef(sourceRef);
    entity.setCommitSha(commitSha);
    entity.setWorkspaceId(workspaceId);
    entity.setFetchedAt(fetchedAt);
    entity.setReady(false);
    entity.setAstSchemaVersion(0);
    entity.setAstReadyAt(null);
    entity.setLastJob(job);
    entity.setWorkspaceSizeBytes(workspaceSizeBytes);
    repository.save(entity);
  }

  @Transactional
  public void markReady(
      String namespace,
      String repoOwner,
      String repoName,
      String sourceRef,
      String commitSha,
      String workspaceId,
      Instant fetchedAt,
      RepoRagIndexJobEntity job,
      long filesTotal,
      long chunksTotal,
      long filesSkipped,
      long workspaceSizeBytes,
      boolean astReady,
      int astSchemaVersion) {
    RepoRagNamespaceStateEntity entity =
        repository.findByNamespace(namespace).orElseGet(RepoRagNamespaceStateEntity::new);
    entity.setNamespace(namespace);
    entity.setRepoOwner(normalize(repoOwner));
    entity.setRepoName(normalize(repoName));
    entity.setSourceRef(sourceRef);
    entity.setCommitSha(commitSha);
    entity.setWorkspaceId(workspaceId);
    entity.setFetchedAt(fetchedAt);
    entity.setFilesTotal(filesTotal);
    entity.setChunksTotal(chunksTotal);
    entity.setFilesSkipped(filesSkipped);
    entity.setLastIndexedAt(Instant.now());
    entity.setReady(true);
    entity.setLastJob(job);
    entity.setWorkspaceSizeBytes(workspaceSizeBytes);
    if (astReady && astSchemaVersion > 0) {
      entity.setAstSchemaVersion(astSchemaVersion);
      entity.setAstReadyAt(Instant.now());
    } else {
      entity.setAstSchemaVersion(0);
      entity.setAstReadyAt(null);
    }
    repository.save(entity);
  }

  @Transactional
  public void markFailed(String namespace, RepoRagIndexJobEntity job) {
    repository
        .findByNamespace(namespace)
        .ifPresent(
          entity -> {
            entity.setReady(false);
            entity.setLastJob(job);
            entity.setAstSchemaVersion(0);
            entity.setAstReadyAt(null);
            repository.save(entity);
          });
  }

  public Optional<RepoRagNamespaceStateEntity> findByNamespace(String namespace) {
    if (!StringUtils.hasText(namespace)) {
      return Optional.empty();
    }
    return repository.findByNamespace(namespace);
  }

  public Optional<RepoRagNamespaceStateEntity> findByRepoOwnerAndRepoName(
      String repoOwner, String repoName) {
    if (!StringUtils.hasText(repoOwner) || !StringUtils.hasText(repoName)) {
      return Optional.empty();
    }
    return repository.findByRepoOwnerAndRepoName(normalize(repoOwner), normalize(repoName));
  }

  public Optional<RepoRagNamespaceStateEntity> findLatestReady() {
    return repository.findFirstByReadyTrueOrderByLastIndexedAtDesc();
  }

  private String normalize(String value) {
    return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
  }
}
