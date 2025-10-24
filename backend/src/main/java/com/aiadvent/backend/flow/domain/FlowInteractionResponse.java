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
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "flow_interaction_response")
public class FlowInteractionResponse {

  @Id
  @GeneratedValue
  @UuidGenerator
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "request_id")
  private FlowInteractionRequest request;

  @Column(name = "chat_session_id", nullable = false)
  private UUID chatSessionId;

  @Column(name = "responded_by")
  private UUID respondedBy;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "payload", columnDefinition = "jsonb")
  private JsonNode payload;

  @Enumerated(EnumType.STRING)
  @Column(name = "source", nullable = false, length = 32)
  private FlowInteractionResponseSource source;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected FlowInteractionResponse() {}

  public FlowInteractionResponse(
      FlowInteractionRequest request,
      UUID chatSessionId,
      UUID respondedBy,
      JsonNode payload,
      FlowInteractionResponseSource source) {
    this.request = request;
    this.chatSessionId = chatSessionId;
    this.respondedBy = respondedBy;
    this.payload = payload;
    this.source = source;
  }

  @PrePersist
  void onPersist() {
    createdAt = Instant.now();
  }

  public UUID getId() {
    return id;
  }

  public FlowInteractionRequest getRequest() {
    return request;
  }

  public UUID getChatSessionId() {
    return chatSessionId;
  }

  public UUID getRespondedBy() {
    return respondedBy;
  }

  public JsonNode getPayload() {
    return payload;
  }

  public FlowInteractionResponseSource getSource() {
    return source;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
