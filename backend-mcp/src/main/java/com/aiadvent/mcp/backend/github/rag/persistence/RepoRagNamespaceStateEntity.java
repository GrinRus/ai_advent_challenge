package com.aiadvent.mcp.backend.github.rag.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

@Entity
@Table(name = "repo_rag_namespace_state")
public class RepoRagNamespaceStateEntity {

  @Id
  @GeneratedValue
  @UuidGenerator
  private UUID id;

  @Column(name = "namespace", length = 256, nullable = false, unique = true)
  private String namespace;

  @Column(name = "repo_owner", length = 180, nullable = false)
  private String repoOwner;

  @Column(name = "repo_name", length = 180, nullable = false)
  private String repoName;

  @Column(name = "source_ref", length = 256)
  private String sourceRef;

  @Column(name = "commit_sha", length = 64)
  private String commitSha;

  @Column(name = "workspace_id", length = 64)
  private String workspaceId;

  @Column(name = "files_total", nullable = false)
  private long filesTotal = 0;

  @Column(name = "chunks_total", nullable = false)
  private long chunksTotal = 0;

  @Column(name = "files_skipped", nullable = false)
  private long filesSkipped = 0;

  @Column(name = "workspace_size_bytes", nullable = false)
  private long workspaceSizeBytes = 0;

  @Column(name = "fetched_at")
  private Instant fetchedAt;

  @Column(name = "last_indexed_at")
  private Instant lastIndexedAt;

  @Column(name = "is_ready", nullable = false)
  private boolean ready = false;

  @Column(name = "ast_schema_version", nullable = false)
  private int astSchemaVersion = 0;

  @Column(name = "ast_ready_at")
  private Instant astReadyAt;

  @Column(name = "graph_ready", nullable = false)
  private boolean graphReady = false;

  @Column(name = "graph_schema_version", nullable = false)
  private int graphSchemaVersion = 0;

  @Column(name = "graph_ready_at")
  private Instant graphReadyAt;

  @Column(name = "graph_sync_error")
  private String graphSyncError;

  @OneToOne
  @JoinColumn(name = "last_job_id")
  private RepoRagIndexJobEntity lastJob;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  public UUID getId() {
    return id;
  }

  public String getNamespace() {
    return namespace;
  }

  public void setNamespace(String namespace) {
    this.namespace = namespace;
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

  public String getSourceRef() {
    return sourceRef;
  }

  public void setSourceRef(String sourceRef) {
    this.sourceRef = sourceRef;
  }

  public String getCommitSha() {
    return commitSha;
  }

  public void setCommitSha(String commitSha) {
    this.commitSha = commitSha;
  }

  public String getWorkspaceId() {
    return workspaceId;
  }

  public void setWorkspaceId(String workspaceId) {
    this.workspaceId = workspaceId;
  }

  public long getFilesTotal() {
    return filesTotal;
  }

  public void setFilesTotal(long filesTotal) {
    this.filesTotal = filesTotal;
  }

  public long getChunksTotal() {
    return chunksTotal;
  }

  public void setChunksTotal(long chunksTotal) {
    this.chunksTotal = chunksTotal;
  }

  public long getFilesSkipped() {
    return filesSkipped;
  }

  public void setFilesSkipped(long filesSkipped) {
    this.filesSkipped = filesSkipped;
  }

  public long getWorkspaceSizeBytes() {
    return workspaceSizeBytes;
  }

  public void setWorkspaceSizeBytes(long workspaceSizeBytes) {
    this.workspaceSizeBytes = workspaceSizeBytes;
  }

  public Instant getFetchedAt() {
    return fetchedAt;
  }

  public void setFetchedAt(Instant fetchedAt) {
    this.fetchedAt = fetchedAt;
  }

  public Instant getLastIndexedAt() {
    return lastIndexedAt;
  }

  public void setLastIndexedAt(Instant lastIndexedAt) {
    this.lastIndexedAt = lastIndexedAt;
  }

  public boolean isReady() {
    return ready;
  }

  public void setReady(boolean ready) {
    this.ready = ready;
  }

  public int getAstSchemaVersion() {
    return astSchemaVersion;
  }

  public void setAstSchemaVersion(int astSchemaVersion) {
    this.astSchemaVersion = Math.max(0, astSchemaVersion);
  }

  public Instant getAstReadyAt() {
    return astReadyAt;
  }

  public void setAstReadyAt(Instant astReadyAt) {
    this.astReadyAt = astReadyAt;
  }

  public boolean isGraphReady() {
    return graphReady;
  }

  public void setGraphReady(boolean graphReady) {
    this.graphReady = graphReady;
  }

  public int getGraphSchemaVersion() {
    return graphSchemaVersion;
  }

  public void setGraphSchemaVersion(int graphSchemaVersion) {
    this.graphSchemaVersion = Math.max(0, graphSchemaVersion);
  }

  public Instant getGraphReadyAt() {
    return graphReadyAt;
  }

  public void setGraphReadyAt(Instant graphReadyAt) {
    this.graphReadyAt = graphReadyAt;
  }

  public String getGraphSyncError() {
    return graphSyncError;
  }

  public void setGraphSyncError(String graphSyncError) {
    this.graphSyncError = graphSyncError;
  }

  public RepoRagIndexJobEntity getLastJob() {
    return lastJob;
  }

  public void setLastJob(RepoRagIndexJobEntity lastJob) {
    this.lastJob = lastJob;
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
  }

  @PreUpdate
  void onUpdate() {
    this.updatedAt = Instant.now();
  }
}
