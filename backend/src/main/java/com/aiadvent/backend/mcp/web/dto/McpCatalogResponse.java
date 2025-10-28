package com.aiadvent.backend.mcp.web.dto;

import java.util.List;

public record McpCatalogResponse(List<McpServer> servers) {

  public enum McpServerStatus {
    UP,
    DOWN,
    UNKNOWN
  }

  public record McpServer(
      String id,
      String displayName,
      String description,
      List<String> tags,
      McpServerStatus status,
      List<McpTool> tools) {}

  public record McpTool(
      String code,
      String displayName,
      String description,
      String mcpToolName,
      int schemaVersion,
      boolean available) {}
}

