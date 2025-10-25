package com.aiadvent.backend.chat.domain;

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
@Table(name = "chat_memory_summary")
public class ChatMemorySummary {

  @Id
  @GeneratedValue
  @UuidGenerator
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "session_id")
  private ChatSession session;

  @Column(name = "source_start_order", nullable = false)
  private int sourceStartOrder;

  @Column(name = "source_end_order", nullable = false)
  private int sourceEndOrder;

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

  protected ChatMemorySummary() {}

  public ChatMemorySummary(
      ChatSession session, int sourceStartOrder, int sourceEndOrder, String summaryText) {
    this.session = session;
    this.sourceStartOrder = sourceStartOrder;
    this.sourceEndOrder = sourceEndOrder;
    this.summaryText = summaryText;
  }

  @PrePersist
  void onPersist() {
    createdAt = Instant.now();
  }

  public UUID getId() {
    return id;
  }

  public ChatSession getSession() {
    return session;
  }

  public void setSession(ChatSession session) {
    this.session = session;
  }

  public int getSourceStartOrder() {
    return sourceStartOrder;
  }

  public void setSourceStartOrder(int sourceStartOrder) {
    this.sourceStartOrder = sourceStartOrder;
  }

  public int getSourceEndOrder() {
    return sourceEndOrder;
  }

  public void setSourceEndOrder(int sourceEndOrder) {
    this.sourceEndOrder = sourceEndOrder;
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
