package com.aiadvent.backend.flow.domain;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "flow_session")
public class FlowSession {

  @Id
  @GeneratedValue
  @UuidGenerator
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "flow_definition_id")
  private FlowDefinition flowDefinition;

  @Column(name = "flow_definition_version", nullable = false)
  private int flowDefinitionVersion;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 32)
  private FlowSessionStatus status;

  @Column(name = "current_step_id", length = 128)
  private String currentStepId;

  @Column(name = "state_version", nullable = false)
  private long stateVersion;

  @Column(name = "current_memory_version", nullable = false)
  private long currentMemoryVersion;

  @Column(name = "chat_session_id")
  private UUID chatSessionId;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "launch_parameters")
  private JsonNode launchParameters;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "shared_context")
  private JsonNode sharedContext;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "launch_overrides")
  private JsonNode launchOverrides;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "telemetry")
  private JsonNode telemetry;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Column(name = "started_at")
  private Instant startedAt;

  @Column(name = "completed_at")
  private Instant completedAt;

  protected FlowSession() {}

  public FlowSession(
      FlowDefinition flowDefinition,
      int flowDefinitionVersion,
      FlowSessionStatus status,
      long stateVersion,
      long currentMemoryVersion) {
    this.flowDefinition = flowDefinition;
    this.flowDefinitionVersion = flowDefinitionVersion;
    this.status = status;
    this.stateVersion = stateVersion;
    this.currentMemoryVersion = currentMemoryVersion;
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

  public UUID getId() {
    return id;
  }

  public FlowDefinition getFlowDefinition() {
    return flowDefinition;
  }

  public int getFlowDefinitionVersion() {
    return flowDefinitionVersion;
  }

  public FlowSessionStatus getStatus() {
    return status;
  }

  public void setStatus(FlowSessionStatus status) {
    this.status = status;
  }

  public String getCurrentStepId() {
    return currentStepId;
  }

  public void setCurrentStepId(String currentStepId) {
    this.currentStepId = currentStepId;
  }

  public long getStateVersion() {
    return stateVersion;
  }

  public void setStateVersion(long stateVersion) {
    this.stateVersion = stateVersion;
  }

  public long getCurrentMemoryVersion() {
    return currentMemoryVersion;
  }

  public void setCurrentMemoryVersion(long currentMemoryVersion) {
    this.currentMemoryVersion = currentMemoryVersion;
  }

  public UUID getChatSessionId() {
    return chatSessionId;
  }

  public void setChatSessionId(UUID chatSessionId) {
    this.chatSessionId = chatSessionId;
  }

  public JsonNode getLaunchParameters() {
    return launchParameters;
  }

  public void setLaunchParameters(JsonNode launchParameters) {
    this.launchParameters = launchParameters;
  }

  public JsonNode getSharedContext() {
    return sharedContext;
  }

  public void setSharedContext(JsonNode sharedContext) {
    this.sharedContext = sharedContext;
  }

  public JsonNode getLaunchOverrides() {
    return launchOverrides;
  }

  public void setLaunchOverrides(JsonNode launchOverrides) {
    this.launchOverrides = launchOverrides;
  }

  public JsonNode getTelemetry() {
    return telemetry;
  }

  public void setTelemetry(JsonNode telemetry) {
    this.telemetry = telemetry;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
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
}
