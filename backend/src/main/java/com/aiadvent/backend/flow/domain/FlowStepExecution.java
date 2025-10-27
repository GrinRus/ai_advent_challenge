package com.aiadvent.backend.flow.domain;

import com.aiadvent.backend.flow.execution.converter.FlowCostPayloadConverter;
import com.aiadvent.backend.flow.execution.converter.FlowStepInputPayloadConverter;
import com.aiadvent.backend.flow.execution.converter.FlowStepOutputPayloadConverter;
import com.aiadvent.backend.flow.execution.converter.FlowUsagePayloadConverter;
import com.aiadvent.backend.flow.execution.model.FlowCostPayload;
import com.aiadvent.backend.flow.execution.model.FlowStepInputPayload;
import com.aiadvent.backend.flow.execution.model.FlowStepOutputPayload;
import com.aiadvent.backend.flow.execution.model.FlowUsagePayload;
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
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "flow_step_execution")
public class FlowStepExecution {

  @Id
  @GeneratedValue
  @UuidGenerator
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "flow_session_id")
  private FlowSession flowSession;

  @Column(name = "step_id", nullable = false, length = 128)
  private String stepId;

  @Column(name = "step_name")
  private String stepName;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 32)
  private FlowStepStatus status;

  @Column(name = "attempt", nullable = false)
  private int attempt = 1;

  @OneToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "interaction_request_id")
  private FlowInteractionRequest interactionRequest;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "agent_version_id")
  private AgentVersion agentVersion;

  @Column(name = "prompt", columnDefinition = "TEXT")
  private String prompt;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "input_payload", columnDefinition = "jsonb")
  @Convert(converter = FlowStepInputPayloadConverter.class)
  private FlowStepInputPayload inputPayload = FlowStepInputPayload.empty();

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "output_payload", columnDefinition = "jsonb")
  @Convert(converter = FlowStepOutputPayloadConverter.class)
  private FlowStepOutputPayload outputPayload = FlowStepOutputPayload.empty();

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "usage", columnDefinition = "jsonb")
  @Convert(converter = FlowUsagePayloadConverter.class)
  private FlowUsagePayload usage = FlowUsagePayload.empty();

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "cost", columnDefinition = "jsonb")
  @Convert(converter = FlowCostPayloadConverter.class)
  private FlowCostPayload cost = FlowCostPayload.empty();

  @Column(name = "error_code", length = 64)
  private String errorCode;

  @Column(name = "error_message", columnDefinition = "TEXT")
  private String errorMessage;

  @Column(name = "started_at")
  private Instant startedAt;

  @Column(name = "completed_at")
  private Instant completedAt;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected FlowStepExecution() {}

  public FlowStepExecution(
      FlowSession flowSession, String stepId, FlowStepStatus status, int attempt) {
    this.flowSession = flowSession;
    this.stepId = stepId;
    this.status = status;
    this.attempt = attempt;
  }

  @PrePersist
  void onPersist() {
    createdAt = Instant.now();
  }

  public UUID getId() {
    return id;
  }

  public FlowSession getFlowSession() {
    return flowSession;
  }

  public String getStepId() {
    return stepId;
  }

  public String getStepName() {
    return stepName;
  }

  public void setStepName(String stepName) {
    this.stepName = stepName;
  }

  public FlowStepStatus getStatus() {
    return status;
  }

  public void setStatus(FlowStepStatus status) {
    this.status = status;
  }

  public int getAttempt() {
    return attempt;
  }

  public void setAttempt(int attempt) {
    this.attempt = attempt;
  }

  public AgentVersion getAgentVersion() {
    return agentVersion;
  }

  public void setAgentVersion(AgentVersion agentVersion) {
    this.agentVersion = agentVersion;
  }

  public FlowInteractionRequest getInteractionRequest() {
    return interactionRequest;
  }

  public void setInteractionRequest(FlowInteractionRequest interactionRequest) {
    this.interactionRequest = interactionRequest;
  }

  public String getPrompt() {
    return prompt;
  }

  public void setPrompt(String prompt) {
    this.prompt = prompt;
  }

  public FlowStepInputPayload getInputPayload() {
    return inputPayload != null ? inputPayload : FlowStepInputPayload.empty();
  }

  public void setInputPayload(FlowStepInputPayload inputPayload) {
    this.inputPayload = inputPayload != null ? inputPayload : FlowStepInputPayload.empty();
  }

  public FlowStepOutputPayload getOutputPayload() {
    return outputPayload != null ? outputPayload : FlowStepOutputPayload.empty();
  }

  public void setOutputPayload(FlowStepOutputPayload outputPayload) {
    this.outputPayload = outputPayload != null ? outputPayload : FlowStepOutputPayload.empty();
  }

  public FlowUsagePayload getUsage() {
    return usage != null ? usage : FlowUsagePayload.empty();
  }

  public void setUsage(FlowUsagePayload usage) {
    this.usage = usage != null ? usage : FlowUsagePayload.empty();
  }

  public FlowCostPayload getCost() {
    return cost != null ? cost : FlowCostPayload.empty();
  }

  public void setCost(FlowCostPayload cost) {
    this.cost = cost != null ? cost : FlowCostPayload.empty();
  }

  public String getErrorCode() {
    return errorCode;
  }

  public void setErrorCode(String errorCode) {
    this.errorCode = errorCode;
  }

  public String getErrorMessage() {
    return errorMessage;
  }

  public void setErrorMessage(String errorMessage) {
    this.errorMessage = errorMessage;
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

  public Instant getCreatedAt() {
    return createdAt;
  }
}
