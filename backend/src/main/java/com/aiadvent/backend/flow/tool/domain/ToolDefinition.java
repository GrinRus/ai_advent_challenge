package com.aiadvent.backend.flow.tool.domain;

import com.aiadvent.backend.shared.json.StringListJsonConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;
import org.springframework.util.StringUtils;

@Entity
@Table(name = "tool_definition")
public class ToolDefinition {

  @Id
  @GeneratedValue
  @UuidGenerator
  private UUID id;

  @Column(name = "code", nullable = false, length = 128, unique = true)
  private String code;

  @Column(name = "display_name", nullable = false, length = 160)
  private String displayName;

  @Column(name = "description", columnDefinition = "TEXT")
  private String description;

  @Column(name = "provider_hint", length = 64)
  private String providerHint;

  @Enumerated(EnumType.STRING)
  @Column(name = "call_type", nullable = false, length = 32)
  private ToolCallType callType;

  @Convert(converter = StringListJsonConverter.class)
  @Column(name = "tags", columnDefinition = "jsonb")
  private List<String> tags;

  @Convert(converter = StringListJsonConverter.class)
  @Column(name = "capabilities", columnDefinition = "jsonb")
  private List<String> capabilities;

  @Column(name = "cost_hint", length = 128)
  private String costHint;

  @Column(name = "icon_url", length = 256)
  private String iconUrl;

  @Column(name = "default_timeout_ms")
  private Long defaultTimeoutMs;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "schema_version_id")
  private ToolSchemaVersion schemaVersion;

  protected ToolDefinition() {}

  public ToolDefinition(
      String code,
      String displayName,
      String description,
      String providerHint,
      ToolCallType callType,
      List<String> tags,
      List<String> capabilities,
      String costHint,
      String iconUrl,
      Long defaultTimeoutMs) {
    this.code = StringUtils.hasText(code) ? code.trim() : null;
    this.displayName = displayName;
    this.description = description;
    this.providerHint = providerHint;
    this.callType = callType != null ? callType : ToolCallType.AUTO;
    this.tags = tags;
    this.capabilities = capabilities;
    this.costHint = costHint;
    this.iconUrl = iconUrl;
    this.defaultTimeoutMs = defaultTimeoutMs;
  }

  public UUID getId() {
    return id;
  }

  public String getCode() {
    return code;
  }

  public String getDisplayName() {
    return displayName;
  }

  public String getDescription() {
    return description;
  }

  public String getProviderHint() {
    return providerHint;
  }

  public ToolCallType getCallType() {
    return callType;
  }

  public List<String> getTags() {
    return tags != null ? tags : List.of();
  }

  public List<String> getCapabilities() {
    return capabilities != null ? capabilities : List.of();
  }

  public String getCostHint() {
    return costHint;
  }

  public String getIconUrl() {
    return iconUrl;
  }

  public Long getDefaultTimeoutMs() {
    return defaultTimeoutMs;
  }

  public ToolSchemaVersion getSchemaVersion() {
    return schemaVersion;
  }

  public void setSchemaVersion(ToolSchemaVersion schemaVersion) {
    this.schemaVersion = schemaVersion;
  }

  public enum ToolCallType {
    AUTO,
    MANUAL,
    MANDATORY
  }
}

