package com.aiadvent.backend.flow.api;

import com.aiadvent.backend.flow.tool.domain.ToolDefinition.ToolCallType;
import java.util.List;
import java.util.UUID;

public record AgentConstructorToolsResponse(List<Tool> tools) {

  public record Tool(
      UUID id,
      String code,
      String displayName,
      String description,
      String providerHint,
      ToolCallType callType,
      List<String> tags,
      List<String> capabilities,
      String costHint,
      String iconUrl,
      Long defaultTimeoutMs,
      SchemaVersion schemaVersion) {}

  public record SchemaVersion(
      UUID id,
      int version,
      String checksum,
      String mcpServer,
      String mcpToolName,
      String transport,
      String authScope) {}
}

