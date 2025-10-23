package com.aiadvent.backend.flow.domain;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
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
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "flow_definition_history")
public class FlowDefinitionHistory {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "flow_definition_id")
  private FlowDefinition flowDefinition;

  @Column(name = "version", nullable = false)
  private int version;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 32)
  private FlowDefinitionStatus status;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "definition", nullable = false, columnDefinition = "jsonb")
  private JsonNode definition;

  @Column(name = "change_notes")
  private String changeNotes;

  @Column(name = "created_by", length = 128)
  private String createdBy;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected FlowDefinitionHistory() {}

  public FlowDefinitionHistory(
      FlowDefinition flowDefinition,
      int version,
      FlowDefinitionStatus status,
      JsonNode definition,
      String changeNotes,
      String createdBy) {
    this.flowDefinition = flowDefinition;
    this.version = version;
    this.status = status;
    this.definition = definition;
    this.changeNotes = changeNotes;
    this.createdBy = createdBy;
  }

  @PrePersist
  void onPersist() {
    createdAt = Instant.now();
  }

  public Long getId() {
    return id;
  }

  public FlowDefinition getFlowDefinition() {
    return flowDefinition;
  }

  public int getVersion() {
    return version;
  }

  public FlowDefinitionStatus getStatus() {
    return status;
  }

  public JsonNode getDefinition() {
    return definition;
  }

  public String getChangeNotes() {
    return changeNotes;
  }

  public String getCreatedBy() {
    return createdBy;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
