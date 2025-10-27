package com.aiadvent.backend.flow.domain;

import com.aiadvent.backend.flow.agent.converter.AgentCapabilityPayloadConverter;
import com.aiadvent.backend.flow.agent.model.AgentCapabilityPayload;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
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
  @Column(name = "payload", columnDefinition = "jsonb")
  @Convert(converter = AgentCapabilityPayloadConverter.class)
  private AgentCapabilityPayload payload = AgentCapabilityPayload.empty();

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected AgentCapability() {}

  public AgentCapability(
      AgentVersion agentVersion, String capability, AgentCapabilityPayload payload) {
    this.agentVersion = agentVersion;
    this.capability = capability;
    this.payload = payload != null ? payload : AgentCapabilityPayload.empty();
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

  public AgentCapabilityPayload getPayload() {
    return payload != null ? payload : AgentCapabilityPayload.empty();
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
