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
  @Column(name = "input_payload")
  private JsonNode inputPayload;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "output_payload")
  private JsonNode outputPayload;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "usage")
  private JsonNode usage;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "cost")
  private JsonNode cost;

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

  public JsonNode getInputPayload() {
    return inputPayload;
  }

  public void setInputPayload(JsonNode inputPayload) {
    this.inputPayload = inputPayload;
  }

  public JsonNode getOutputPayload() {
    return outputPayload;
  }

  public void setOutputPayload(JsonNode outputPayload) {
    this.outputPayload = outputPayload;
  }

  public JsonNode getUsage() {
    return usage;
  }

  public void setUsage(JsonNode usage) {
    this.usage = usage;
  }

  public JsonNode getCost() {
    return cost;
  }

  public void setCost(JsonNode cost) {
    this.cost = cost;
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
