package com.aiadvent.backend.flow.domain;

import com.aiadvent.backend.flow.blueprint.FlowBlueprint;
import com.aiadvent.backend.flow.blueprint.FlowBlueprintConverter;
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
import java.time.Instant;

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

  @Convert(converter = FlowBlueprintConverter.class)
  @Column(name = "definition", nullable = false, columnDefinition = "jsonb")
  private FlowBlueprint definition;

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
      FlowBlueprint definition,
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

  public FlowBlueprint getDefinition() {
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
