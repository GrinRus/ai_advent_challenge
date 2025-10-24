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
@Table(name = "flow_interaction_request")
public class FlowInteractionRequest {

  @Id
  @GeneratedValue
  @UuidGenerator
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "flow_session_id")
  private FlowSession flowSession;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "flow_step_execution_id")
  private FlowStepExecution flowStepExecution;

  @Column(name = "chat_session_id", nullable = false)
  private UUID chatSessionId;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "agent_version_id")
  private AgentVersion agentVersion;

  @Enumerated(EnumType.STRING)
  @Column(name = "type", nullable = false, length = 64)
  private FlowInteractionType type;

  @Column(name = "title", length = 512)
  private String title;

  @Column(name = "description", columnDefinition = "TEXT")
  private String description;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "payload_schema", columnDefinition = "jsonb")
  private JsonNode payloadSchema;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "suggested_actions", columnDefinition = "jsonb")
  private JsonNode suggestedActions;

  @Column(name = "due_at")
  private Instant dueAt;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 32)
  private FlowInteractionStatus status;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected FlowInteractionRequest() {}

  public FlowInteractionRequest(
      FlowSession flowSession,
      FlowStepExecution flowStepExecution,
      UUID chatSessionId,
      AgentVersion agentVersion,
      FlowInteractionType type,
      FlowInteractionStatus status) {
    this.flowSession = flowSession;
    this.flowStepExecution = flowStepExecution;
    this.chatSessionId = chatSessionId;
    this.agentVersion = agentVersion;
    this.type = type;
    this.status = status;
  }

  @PrePersist
  void onPersist() {
    Instant now = Instant.now();
    createdAt = now;
    updatedAt = now;
    if (status == null) {
      status = FlowInteractionStatus.PENDING;
    }
  }

  @PreUpdate
  void onUpdate() {
    updatedAt = Instant.now();
  }

  public UUID getId() {
    return id;
  }

  public FlowSession getFlowSession() {
    return flowSession;
  }

  public FlowStepExecution getFlowStepExecution() {
    return flowStepExecution;
  }

  public UUID getChatSessionId() {
    return chatSessionId;
  }

  public void setChatSessionId(UUID chatSessionId) {
    this.chatSessionId = chatSessionId;
  }

  public AgentVersion getAgentVersion() {
    return agentVersion;
  }

  public FlowInteractionType getType() {
    return type;
  }

  public void setType(FlowInteractionType type) {
    this.type = type;
  }

  public String getTitle() {
    return title;
  }

  public void setTitle(String title) {
    this.title = title;
  }

  public String getDescription() {
    return description;
  }

  public void setDescription(String description) {
    this.description = description;
  }

  public JsonNode getPayloadSchema() {
    return payloadSchema;
  }

  public void setPayloadSchema(JsonNode payloadSchema) {
    this.payloadSchema = payloadSchema;
  }

  public JsonNode getSuggestedActions() {
    return suggestedActions;
  }

  public void setSuggestedActions(JsonNode suggestedActions) {
    this.suggestedActions = suggestedActions;
  }

  public Instant getDueAt() {
    return dueAt;
  }

  public void setDueAt(Instant dueAt) {
    this.dueAt = dueAt;
  }

  public FlowInteractionStatus getStatus() {
    return status;
  }

  public void setStatus(FlowInteractionStatus status) {
    this.status = status;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
