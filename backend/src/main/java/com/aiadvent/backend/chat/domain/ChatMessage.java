package com.aiadvent.backend.chat.domain;

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
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "chat_message")
public class ChatMessage {

  @Id @GeneratedValue @UuidGenerator private UUID id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "session_id", nullable = false)
  private ChatSession session;

  @Enumerated(EnumType.STRING)
  @Column(name = "role", nullable = false, length = 32)
  private ChatRole role;

  @Column(name = "content", nullable = false, columnDefinition = "TEXT")
  private String content;

  @Column(name = "sequence_number", nullable = false)
  private Integer sequenceNumber;

  @Column(name = "provider", length = 64)
  private String provider;

  @Column(name = "model", length = 128)
  private String model;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "structured_payload")
  private JsonNode structuredPayload;

  @Column(name = "prompt_tokens")
  private Integer promptTokens;

  @Column(name = "completion_tokens")
  private Integer completionTokens;

  @Column(name = "total_tokens")
  private Integer totalTokens;

  @Column(name = "input_cost", precision = 19, scale = 8)
  private BigDecimal inputCost;

  @Column(name = "output_cost", precision = 19, scale = 8)
  private BigDecimal outputCost;

  @Column(name = "currency", length = 8)
  private String currency;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected ChatMessage() {}

  public ChatMessage(
      ChatSession session,
      ChatRole role,
      String content,
      Integer sequenceNumber,
      String provider,
      String model) {
    this(session, role, content, sequenceNumber, provider, model, null);
  }

  public ChatMessage(
      ChatSession session,
      ChatRole role,
      String content,
      Integer sequenceNumber,
      String provider,
      String model,
      JsonNode structuredPayload) {
    this.session = session;
    this.role = role;
    this.content = content;
    this.sequenceNumber = sequenceNumber;
    this.provider = provider;
    this.model = model;
    this.structuredPayload = structuredPayload;
  }

  @PrePersist
  protected void onPersist() {
    this.createdAt = Instant.now();
  }

  public UUID getId() {
    return id;
  }

  public ChatSession getSession() {
    return session;
  }

  public ChatRole getRole() {
    return role;
  }

  public String getContent() {
    return content;
  }

  public Integer getSequenceNumber() {
    return sequenceNumber;
  }

  public String getProvider() {
    return provider;
  }

  public String getModel() {
    return model;
  }

  public JsonNode getStructuredPayload() {
    return structuredPayload;
  }

  public Integer getPromptTokens() {
    return promptTokens;
  }

  public Integer getCompletionTokens() {
    return completionTokens;
  }

  public Integer getTotalTokens() {
    return totalTokens;
  }

  public BigDecimal getInputCost() {
    return inputCost;
  }

  public BigDecimal getOutputCost() {
    return outputCost;
  }

  public String getCurrency() {
    return currency;
  }

  public void applyUsage(
      Integer promptTokens,
      Integer completionTokens,
      Integer totalTokens,
      BigDecimal inputCost,
      BigDecimal outputCost,
      String currency) {
    this.promptTokens = promptTokens;
    this.completionTokens = completionTokens;
    this.totalTokens = totalTokens;
    this.inputCost = inputCost;
    this.outputCost = outputCost;
    this.currency = currency;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
