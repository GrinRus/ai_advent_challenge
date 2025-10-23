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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "agent_capability")
public class AgentCapability {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "agent_version_id")
  private AgentVersion agentVersion;

  @Column(name = "capability", nullable = false, length = 128)
  private String capability;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "payload")
  private JsonNode payload;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected AgentCapability() {}

  public AgentCapability(AgentVersion agentVersion, String capability, JsonNode payload) {
    this.agentVersion = agentVersion;
    this.capability = capability;
    this.payload = payload;
  }

  @PrePersist
  void onPersist() {
    createdAt = Instant.now();
  }

  public Long getId() {
    return id;
  }

  public AgentVersion getAgentVersion() {
    return agentVersion;
  }

  public String getCapability() {
    return capability;
  }

  public JsonNode getPayload() {
    return payload;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
