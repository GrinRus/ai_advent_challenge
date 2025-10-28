package com.aiadvent.backend.mcp.web.dto;

import com.aiadvent.backend.mcp.web.dto.McpCatalogResponse.McpServerStatus;
import java.time.Instant;
import java.util.List;

public record McpHealthEvent(
    String serverId,
    McpServerStatus status,
    int toolCount,
    List<String> availableTools,
    List<String> unavailableTools,
    Instant timestamp) {}

