package com.aiadvent.backend.flow.domain;

import com.aiadvent.backend.chat.config.ChatProviderType;
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
@Table(name = "agent_version")
public class AgentVersion {

  @Id
  @GeneratedValue
  @UuidGenerator
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "agent_definition_id")
  private AgentDefinition agentDefinition;

  @Column(name = "version", nullable = false)
  private int version;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 32)
  private AgentVersionStatus status;

  @Enumerated(EnumType.STRING)
  @Column(name = "provider_type", nullable = false, length = 32)
  private ChatProviderType providerType;

  @Column(name = "provider_id", nullable = false, length = 64)
  private String providerId;

  @Column(name = "model_id", nullable = false, length = 128)
  private String modelId;

  @Column(name = "system_prompt", columnDefinition = "TEXT")
  private String systemPrompt;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "default_options")
  private JsonNode defaultOptions;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "tool_bindings")
  private JsonNode toolBindings;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "cost_profile")
  private JsonNode costProfile;

  @Column(name = "sync_only", nullable = false)
  private boolean syncOnly = true;

  @Column(name = "max_tokens")
  private Integer maxTokens;

  @Column(name = "created_by", length = 128)
  private String createdBy;

  @Column(name = "updated_by", length = 128)
  private String updatedBy;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "published_at")
  private Instant publishedAt;

  protected AgentVersion() {}

  public AgentVersion(
      AgentDefinition agentDefinition,
      int version,
      AgentVersionStatus status,
      ChatProviderType providerType,
      String providerId,
      String modelId) {
    this.agentDefinition = agentDefinition;
    this.version = version;
    this.status = status;
    this.providerType = providerType;
    this.providerId = providerId;
    this.modelId = modelId;
  }

  @PrePersist
  void onPersist() {
    createdAt = Instant.now();
  }

  public UUID getId() {
    return id;
  }

  public AgentDefinition getAgentDefinition() {
    return agentDefinition;
  }

  public int getVersion() {
    return version;
  }

  public AgentVersionStatus getStatus() {
    return status;
  }

  public void setStatus(AgentVersionStatus status) {
    this.status = status;
  }

  public ChatProviderType getProviderType() {
    return providerType;
  }

  public String getProviderId() {
    return providerId;
  }

  public String getModelId() {
    return modelId;
  }

  public String getSystemPrompt() {
    return systemPrompt;
  }

  public void setSystemPrompt(String systemPrompt) {
    this.systemPrompt = systemPrompt;
  }

  public JsonNode getDefaultOptions() {
    return defaultOptions;
  }

  public void setDefaultOptions(JsonNode defaultOptions) {
    this.defaultOptions = defaultOptions;
  }

  public JsonNode getToolBindings() {
    return toolBindings;
  }

  public void setToolBindings(JsonNode toolBindings) {
    this.toolBindings = toolBindings;
  }

  public JsonNode getCostProfile() {
    return costProfile;
  }

  public void setCostProfile(JsonNode costProfile) {
    this.costProfile = costProfile;
  }

  public boolean isSyncOnly() {
    return syncOnly;
  }

  public void setSyncOnly(boolean syncOnly) {
    this.syncOnly = syncOnly;
  }

  public Integer getMaxTokens() {
    return maxTokens;
  }

  public void setMaxTokens(Integer maxTokens) {
    this.maxTokens = maxTokens;
  }

  public String getCreatedBy() {
    return createdBy;
  }

  public void setCreatedBy(String createdBy) {
    this.createdBy = createdBy;
  }

  public String getUpdatedBy() {
    return updatedBy;
  }

  public void setUpdatedBy(String updatedBy) {
    this.updatedBy = updatedBy;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getPublishedAt() {
    return publishedAt;
  }

  public void setPublishedAt(Instant publishedAt) {
    this.publishedAt = publishedAt;
  }
}
