package com.aiadvent.backend.flow.domain;

import com.aiadvent.backend.chat.provider.model.UsageSource;
import com.aiadvent.backend.flow.execution.converter.FlowEventPayloadConverter;
import com.aiadvent.backend.flow.execution.model.FlowEventPayload;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
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
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "flow_event")
public class FlowEvent {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "flow_session_id")
  private FlowSession flowSession;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "flow_step_execution_id")
  private FlowStepExecution flowStepExecution;

  @Enumerated(EnumType.STRING)
  @Column(name = "event_type", nullable = false, length = 64)
  private FlowEventType eventType;

  @Column(name = "status", length = 32)
  private String status;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "payload", columnDefinition = "jsonb")
  @Convert(converter = FlowEventPayloadConverter.class)
  private FlowEventPayload payload = FlowEventPayload.empty();

  @Column(name = "cost", precision = 19, scale = 8)
  private BigDecimal cost;

  @Column(name = "tokens_prompt")
  private Integer tokensPrompt;

  @Column(name = "tokens_completion")
  private Integer tokensCompletion;

  @Column(name = "trace_id", length = 128)
  private String traceId;

  @Column(name = "span_id", length = 128)
  private String spanId;

  @Enumerated(EnumType.STRING)
  @Column(name = "usage_source", length = 32)
  private UsageSource usageSource;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected FlowEvent() {}

  public FlowEvent(
      FlowSession flowSession, FlowEventType eventType, String status, FlowEventPayload payload) {
    this.flowSession = flowSession;
    this.eventType = eventType;
    this.status = status;
    this.payload = payload != null ? payload : FlowEventPayload.empty();
  }

  @PrePersist
  void onPersist() {
    createdAt = Instant.now();
  }

  public Long getId() {
    return id;
  }

  public FlowSession getFlowSession() {
    return flowSession;
  }

  public FlowStepExecution getFlowStepExecution() {
    return flowStepExecution;
  }

  public void setFlowStepExecution(FlowStepExecution flowStepExecution) {
    this.flowStepExecution = flowStepExecution;
  }

  public FlowEventType getEventType() {
    return eventType;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String status) {
    this.status = status;
  }

  public FlowEventPayload getPayload() {
    return payload != null ? payload : FlowEventPayload.empty();
  }

  public void setPayload(FlowEventPayload payload) {
    this.payload = payload != null ? payload : FlowEventPayload.empty();
  }

  public BigDecimal getCost() {
    return cost;
  }

  public void setCost(BigDecimal cost) {
    this.cost = cost;
  }

  public Integer getTokensPrompt() {
    return tokensPrompt;
  }

  public void setTokensPrompt(Integer tokensPrompt) {
    this.tokensPrompt = tokensPrompt;
  }

  public Integer getTokensCompletion() {
    return tokensCompletion;
  }

  public void setTokensCompletion(Integer tokensCompletion) {
    this.tokensCompletion = tokensCompletion;
  }

  public String getTraceId() {
    return traceId;
  }

  public void setTraceId(String traceId) {
    this.traceId = traceId;
  }

  public String getSpanId() {
    return spanId;
  }

  public void setSpanId(String spanId) {
    this.spanId = spanId;
  }

  public UsageSource getUsageSource() {
    return usageSource;
  }

  public void setUsageSource(UsageSource usageSource) {
    this.usageSource = usageSource;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
