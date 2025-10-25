package com.aiadvent.backend.chat.domain;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "chat_session")
public class ChatSession {

  @Id @GeneratedValue @UuidGenerator private UUID id;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "summary_until_order", nullable = false)
  private int summaryUntilOrder;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "summary_metadata")
  private JsonNode summaryMetadata;

  @PrePersist
  protected void onPersist() {
    this.createdAt = Instant.now();
  }

  public UUID getId() {
    return id;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public int getSummaryUntilOrder() {
    return summaryUntilOrder;
  }

  public void setSummaryUntilOrder(int summaryUntilOrder) {
    this.summaryUntilOrder = summaryUntilOrder;
  }

  public JsonNode getSummaryMetadata() {
    return summaryMetadata;
  }

  public void setSummaryMetadata(JsonNode summaryMetadata) {
    this.summaryMetadata = summaryMetadata;
  }
}
