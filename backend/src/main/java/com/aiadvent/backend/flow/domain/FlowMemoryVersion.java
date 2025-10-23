package com.aiadvent.backend.flow.domain;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "flow_memory_version")
public class FlowMemoryVersion {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "flow_session_id")
  private FlowSession flowSession;

  @Column(name = "channel", nullable = false, length = 64)
  private String channel;

  @Column(name = "version", nullable = false)
  private long version;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "data", nullable = false, columnDefinition = "jsonb")
  private JsonNode data;

  @Column(name = "parent_version_id")
  private Long parentVersionId;

  @Column(name = "created_by_step_id")
  private UUID createdByStepId;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected FlowMemoryVersion() {}

  public FlowMemoryVersion(
      FlowSession flowSession, String channel, long version, JsonNode data, Long parentVersionId) {
    this.flowSession = flowSession;
    this.channel = channel;
    this.version = version;
    this.data = data;
    this.parentVersionId = parentVersionId;
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

  public String getChannel() {
    return channel;
  }

  public long getVersion() {
    return version;
  }

  public JsonNode getData() {
    return data;
  }

  public void setData(JsonNode data) {
    this.data = data;
  }

  public Long getParentVersionId() {
    return parentVersionId;
  }

  public void setParentVersionId(Long parentVersionId) {
    this.parentVersionId = parentVersionId;
  }

  public UUID getCreatedByStepId() {
    return createdByStepId;
  }

  public void setCreatedByStepId(UUID createdByStepId) {
    this.createdByStepId = createdByStepId;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
