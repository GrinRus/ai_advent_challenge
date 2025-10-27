package com.aiadvent.backend.flow.domain;

import com.aiadvent.backend.flow.session.converter.FlowLaunchParametersConverter;
import com.aiadvent.backend.flow.session.converter.FlowOverridesConverter;
import com.aiadvent.backend.flow.session.converter.FlowSharedContextConverter;
import com.aiadvent.backend.flow.session.converter.FlowTelemetrySnapshotConverter;
import com.aiadvent.backend.flow.session.model.FlowLaunchParameters;
import com.aiadvent.backend.flow.session.model.FlowOverrides;
import com.aiadvent.backend.flow.session.model.FlowSharedContext;
import com.aiadvent.backend.flow.session.model.FlowTelemetrySnapshot;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
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
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.annotations.JdbcTypeCode;
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
  @Column(name = "launch_parameters", columnDefinition = "jsonb")
  @Convert(converter = FlowLaunchParametersConverter.class)
  private FlowLaunchParameters launchParameters = FlowLaunchParameters.empty();

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "shared_context", columnDefinition = "jsonb")
  @Convert(converter = FlowSharedContextConverter.class)
  private FlowSharedContext sharedContext = FlowSharedContext.empty();

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "launch_overrides", columnDefinition = "jsonb")
  @Convert(converter = FlowOverridesConverter.class)
  private FlowOverrides launchOverrides = FlowOverrides.empty();

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "telemetry", columnDefinition = "jsonb")
  @Convert(converter = FlowTelemetrySnapshotConverter.class)
  private FlowTelemetrySnapshot telemetry = FlowTelemetrySnapshot.empty();

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

  public FlowLaunchParameters getLaunchParameters() {
    return launchParameters != null ? launchParameters : FlowLaunchParameters.empty();
  }

  public void setLaunchParameters(FlowLaunchParameters launchParameters) {
    this.launchParameters = launchParameters != null ? launchParameters : FlowLaunchParameters.empty();
  }

  public FlowSharedContext getSharedContext() {
    return sharedContext != null ? sharedContext : FlowSharedContext.empty();
  }

  public void setSharedContext(FlowSharedContext sharedContext) {
    this.sharedContext = sharedContext != null ? sharedContext : FlowSharedContext.empty();
  }

  public FlowOverrides getLaunchOverrides() {
    return launchOverrides != null ? launchOverrides : FlowOverrides.empty();
  }

  public void setLaunchOverrides(FlowOverrides launchOverrides) {
    this.launchOverrides = launchOverrides != null ? launchOverrides : FlowOverrides.empty();
  }

  public FlowTelemetrySnapshot getTelemetry() {
    return telemetry != null ? telemetry : FlowTelemetrySnapshot.empty();
  }

  public void setTelemetry(FlowTelemetrySnapshot telemetry) {
    this.telemetry = telemetry != null ? telemetry : FlowTelemetrySnapshot.empty();
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
