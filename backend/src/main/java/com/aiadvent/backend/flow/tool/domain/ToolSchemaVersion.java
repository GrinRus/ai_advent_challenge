package com.aiadvent.backend.flow.tool.domain;

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
@Table(name = "tool_schema_version")
public class ToolSchemaVersion {

  @Id
  @GeneratedValue
  @UuidGenerator
  private UUID id;

  @Column(name = "tool_code", nullable = false, length = 128)
  private String toolCode;

  @Column(name = "version", nullable = false)
  private int version;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "request_schema", columnDefinition = "jsonb")
  private JsonNode requestSchema;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "response_schema", columnDefinition = "jsonb")
  private JsonNode responseSchema;

  @Column(name = "schema_checksum", length = 64)
  private String schemaChecksum;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "examples", columnDefinition = "jsonb")
  private JsonNode examples;

  @Column(name = "mcp_server", length = 128)
  private String mcpServer;

  @Column(name = "mcp_tool_name", length = 128)
  private String mcpToolName;

  @Column(name = "transport", length = 64)
  private String transport;

  @Column(name = "auth_scope", length = 128)
  private String authScope;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  protected ToolSchemaVersion() {}

  public ToolSchemaVersion(
      String toolCode,
      int version,
      JsonNode requestSchema,
      JsonNode responseSchema,
      String schemaChecksum,
      JsonNode examples,
      String mcpServer,
      String mcpToolName,
      String transport,
      String authScope) {
    this.toolCode = toolCode;
    this.version = version;
    this.requestSchema = requestSchema;
    this.responseSchema = responseSchema;
    this.schemaChecksum = schemaChecksum;
    this.examples = examples;
    this.mcpServer = mcpServer;
    this.mcpToolName = mcpToolName;
    this.transport = transport;
    this.authScope = authScope;
  }

  @PrePersist
  void onPersist() {
    if (createdAt == null) {
      createdAt = Instant.now();
    }
  }

  public UUID getId() {
    return id;
  }

  public String getToolCode() {
    return toolCode;
  }

  public int getVersion() {
    return version;
  }

  public JsonNode getRequestSchema() {
    return requestSchema;
  }

  public JsonNode getResponseSchema() {
    return responseSchema;
  }

  public String getSchemaChecksum() {
    return schemaChecksum;
  }

  public JsonNode getExamples() {
    return examples;
  }

  public String getMcpServer() {
    return mcpServer;
  }

  public String getMcpToolName() {
    return mcpToolName;
  }

  public String getTransport() {
    return transport;
  }

  public String getAuthScope() {
    return authScope;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}

