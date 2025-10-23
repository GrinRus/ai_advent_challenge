package com.aiadvent.backend.flow.domain;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "flow_job")
public class FlowJob {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "flow_session_id")
  private FlowSession flowSession;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "flow_step_execution_id")
  private FlowStepExecution flowStepExecution;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
  private JsonNode payload;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 32)
  private FlowJobStatus status;

  @Column(name = "retry_count", nullable = false)
  private int retryCount;

  @Column(name = "scheduled_at")
  private Instant scheduledAt;

  @Column(name = "locked_at")
  private Instant lockedAt;

  @Column(name = "locked_by", length = 128)
  private String lockedBy;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected FlowJob() {}

  public FlowJob(JsonNode payload, FlowJobStatus status) {
    this.payload = payload;
    this.status = status;
  }

  @PrePersist
  void onPersist() {
    Instant now = Instant.now();
    createdAt = now;
    updatedAt = now;
  }

  @PreUpdate
  void onUpdate() {
    updatedAt = Instant.now();
  }

  public Long getId() {
    return id;
  }

  public FlowSession getFlowSession() {
    return flowSession;
  }

  public void setFlowSession(FlowSession flowSession) {
    this.flowSession = flowSession;
  }

  public FlowStepExecution getFlowStepExecution() {
    return flowStepExecution;
  }

  public void setFlowStepExecution(FlowStepExecution flowStepExecution) {
    this.flowStepExecution = flowStepExecution;
  }

  public JsonNode getPayload() {
    return payload;
  }

  public void setPayload(JsonNode payload) {
    this.payload = payload;
  }

  public FlowJobStatus getStatus() {
    return status;
  }

  public void setStatus(FlowJobStatus status) {
    this.status = status;
  }

  public int getRetryCount() {
    return retryCount;
  }

  public void setRetryCount(int retryCount) {
    this.retryCount = retryCount;
  }

  public Instant getScheduledAt() {
    return scheduledAt;
  }

  public void setScheduledAt(Instant scheduledAt) {
    this.scheduledAt = scheduledAt;
  }

  public Instant getLockedAt() {
    return lockedAt;
  }

  public void setLockedAt(Instant lockedAt) {
    this.lockedAt = lockedAt;
  }

  public String getLockedBy() {
    return lockedBy;
  }

  public void setLockedBy(String lockedBy) {
    this.lockedBy = lockedBy;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
