package com.aiadvent.mcp.backend.github.rag.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "repo_rag_index_job")
public class RepoRagIndexJobEntity {

  @Id
  @GeneratedValue
  @UuidGenerator
  private UUID id;

  @Column(name = "repo_owner", length = 180, nullable = false)
  private String repoOwner;

  @Column(name = "repo_name", length = 180, nullable = false)
  private String repoName;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", length = 32, nullable = false)
  private RepoRagJobStatus status = RepoRagJobStatus.QUEUED;

  @Column(name = "attempt", nullable = false)
  private int attempt = 0;

  @Column(name = "max_attempts", nullable = false)
  private int maxAttempts = 5;

  @Column(name = "queued_at", nullable = false, updatable = false)
  private Instant queuedAt;

  @Column(name = "started_at")
  private Instant startedAt;

  @Column(name = "completed_at")
  private Instant completedAt;

  @Column(name = "files_total", nullable = false)
  private long filesTotal = 0;

  @Column(name = "files_processed", nullable = false)
  private long filesProcessed = 0;

  @Column(name = "chunks_total", nullable = false)
  private long chunksTotal = 0;

  @Column(name = "chunks_processed", nullable = false)
  private long chunksProcessed = 0;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "last_error", columnDefinition = "jsonb")
  private JsonNode lastError;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  public UUID getId() {
    return id;
  }

  public String getRepoOwner() {
    return repoOwner;
  }

  public void setRepoOwner(String repoOwner) {
    this.repoOwner = repoOwner;
  }

  public String getRepoName() {
    return repoName;
  }

  public void setRepoName(String repoName) {
    this.repoName = repoName;
  }

  public RepoRagJobStatus getStatus() {
    return status;
  }

  public void setStatus(RepoRagJobStatus status) {
    this.status = status;
  }

  public int getAttempt() {
    return attempt;
  }

  public void setAttempt(int attempt) {
    this.attempt = attempt;
  }

  public int getMaxAttempts() {
    return maxAttempts;
  }

  public void setMaxAttempts(int maxAttempts) {
    this.maxAttempts = maxAttempts;
  }

  public Instant getQueuedAt() {
    return queuedAt;
  }

  public void setQueuedAt(Instant queuedAt) {
    this.queuedAt = queuedAt;
  }

  public Instant getStartedAt() {
    return startedAt;
  }

  public void setStartedAt(Instant startedAt) {
    this.startedAt = startedAt;
  }

  public Instant getCompletedAt() {
    return completedAt;
  }

  public void setCompletedAt(Instant completedAt) {
    this.completedAt = completedAt;
  }

  public long getFilesTotal() {
    return filesTotal;
  }

  public void setFilesTotal(long filesTotal) {
    this.filesTotal = filesTotal;
  }

  public long getFilesProcessed() {
    return filesProcessed;
  }

  public void setFilesProcessed(long filesProcessed) {
    this.filesProcessed = filesProcessed;
  }

  public long getChunksTotal() {
    return chunksTotal;
  }

  public void setChunksTotal(long chunksTotal) {
    this.chunksTotal = chunksTotal;
  }

  public long getChunksProcessed() {
    return chunksProcessed;
  }

  public void setChunksProcessed(long chunksProcessed) {
    this.chunksProcessed = chunksProcessed;
  }

  public JsonNode getLastError() {
    return lastError;
  }

  public void setLastError(JsonNode lastError) {
    this.lastError = lastError;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  @PrePersist
  void onCreate() {
    Instant now = Instant.now();
    this.createdAt = now;
    this.updatedAt = now;
    if (this.queuedAt == null) {
      this.queuedAt = now;
    }
  }

  @PreUpdate
  void onUpdate() {
    this.updatedAt = Instant.now();
  }
}
