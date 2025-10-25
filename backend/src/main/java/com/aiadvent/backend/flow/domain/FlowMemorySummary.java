package com.aiadvent.backend.flow.domain;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "flow_memory_summary")
public class FlowMemorySummary {

  @Id
  @GeneratedValue
  @UuidGenerator
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "flow_session_id")
  private FlowSession flowSession;

  @Column(name = "channel", nullable = false, length = 64)
  private String channel;

  @Column(name = "step_id", length = 128)
  private String stepId;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "agent_version_id")
  private AgentVersion agentVersion;

  @Column(name = "attempt_start")
  private Integer attemptStart;

  @Column(name = "attempt_end")
  private Integer attemptEnd;

  @Column(name = "source_version_start", nullable = false)
  private long sourceVersionStart;

  @Column(name = "source_version_end", nullable = false)
  private long sourceVersionEnd;

  @Column(name = "summary_text", nullable = false, columnDefinition = "text")
  private String summaryText;

  @Column(name = "token_count")
  private Long tokenCount;

  @Column(name = "language", length = 16)
  private String language;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "metadata", columnDefinition = "jsonb")
  private JsonNode metadata;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected FlowMemorySummary() {}

  public FlowMemorySummary(
      FlowSession flowSession,
      String channel,
      long sourceVersionStart,
      long sourceVersionEnd,
      String summaryText) {
    this.flowSession = flowSession;
    this.channel = channel;
    this.sourceVersionStart = sourceVersionStart;
    this.sourceVersionEnd = sourceVersionEnd;
    this.summaryText = summaryText;
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

  public void setFlowSession(FlowSession flowSession) {
    this.flowSession = flowSession;
  }

  public String getChannel() {
    return channel;
  }

  public void setChannel(String channel) {
    this.channel = channel;
  }

  public String getStepId() {
    return stepId;
  }

  public void setStepId(String stepId) {
    this.stepId = stepId;
  }

  public AgentVersion getAgentVersion() {
    return agentVersion;
  }

  public void setAgentVersion(AgentVersion agentVersion) {
    this.agentVersion = agentVersion;
  }

  public Integer getAttemptStart() {
    return attemptStart;
  }

  public void setAttemptStart(Integer attemptStart) {
    this.attemptStart = attemptStart;
  }

  public Integer getAttemptEnd() {
    return attemptEnd;
  }

  public void setAttemptEnd(Integer attemptEnd) {
    this.attemptEnd = attemptEnd;
  }

  public long getSourceVersionStart() {
    return sourceVersionStart;
  }

  public void setSourceVersionStart(long sourceVersionStart) {
    this.sourceVersionStart = sourceVersionStart;
  }

  public long getSourceVersionEnd() {
    return sourceVersionEnd;
  }

  public void setSourceVersionEnd(long sourceVersionEnd) {
    this.sourceVersionEnd = sourceVersionEnd;
  }

  public String getSummaryText() {
    return summaryText;
  }

  public void setSummaryText(String summaryText) {
    this.summaryText = summaryText;
  }

  public Long getTokenCount() {
    return tokenCount;
  }

  public void setTokenCount(Long tokenCount) {
    this.tokenCount = tokenCount;
  }

  public String getLanguage() {
    return language;
  }

  public void setLanguage(String language) {
    this.language = language;
  }

  public JsonNode getMetadata() {
    return metadata;
  }

  public void setMetadata(JsonNode metadata) {
    this.metadata = metadata;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
