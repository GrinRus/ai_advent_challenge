package com.aiadvent.backend.mcp.service;

import com.aiadvent.backend.flow.tool.domain.ToolDefinition;
import com.aiadvent.backend.flow.tool.domain.ToolSchemaVersion;
import com.aiadvent.backend.flow.tool.persistence.ToolDefinitionRepository;
import com.aiadvent.backend.mcp.config.McpCatalogProperties;
import com.aiadvent.backend.mcp.web.dto.McpCatalogResponse;
import com.aiadvent.backend.mcp.web.dto.McpCatalogResponse.McpServerStatus;
import static com.aiadvent.backend.mcp.util.McpToolNameSanitizer.sanitize;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class McpCatalogService {

  private static final Logger log = LoggerFactory.getLogger(McpCatalogService.class);

  private final ToolDefinitionRepository toolDefinitionRepository;
  private final ObjectProvider<SyncMcpToolCallbackProvider> toolCallbackProvider;
  private final McpCatalogProperties catalogProperties;

  public McpCatalogService(
      ToolDefinitionRepository toolDefinitionRepository,
      ObjectProvider<SyncMcpToolCallbackProvider> toolCallbackProvider,
      McpCatalogProperties catalogProperties) {
    this.toolDefinitionRepository = toolDefinitionRepository;
    this.toolCallbackProvider = toolCallbackProvider;
    this.catalogProperties = catalogProperties;
  }

  public McpCatalogResponse getCatalog() {
    Map<String, List<McpCatalogResponse.McpTool>> toolsByServer = collectToolsByServer();
    Map<String, McpCatalogProperties.ServerProperties> metadata =
        catalogProperties.getServers();

    metadata.keySet().forEach(
        serverId -> toolsByServer.computeIfAbsent(serverId, key -> new ArrayList<>()));

    Map<String, McpCatalogResponse.McpServer> servers = new LinkedHashMap<>();
    for (Map.Entry<String, List<McpCatalogResponse.McpTool>> entry : toolsByServer.entrySet()) {
      String serverId = entry.getKey();
      List<McpCatalogResponse.McpTool> tools = entry.getValue();

      McpCatalogProperties.ServerProperties serverProps = metadata.get(serverId);
      String displayName =
          Optional.ofNullable(serverProps)
              .map(McpCatalogProperties.ServerProperties::getDisplayName)
              .filter(StringUtils::hasText)
              .orElseGet(() -> humanizeServerId(serverId));
      String description =
          Optional.ofNullable(serverProps)
              .map(McpCatalogProperties.ServerProperties::getDescription)
              .orElse(null);
      List<String> tags =
          Optional.ofNullable(serverProps)
              .map(McpCatalogProperties.ServerProperties::getTags)
              .orElse(List.of());
      String securityPolicy =
          Optional.ofNullable(serverProps)
              .map(McpCatalogProperties.ServerProperties::getSecurityPolicy)
              .filter(StringUtils::hasText)
              .orElse(null);

      McpServerStatus status = deriveStatus(serverId, tools);

      servers.put(
          serverId,
          new McpCatalogResponse.McpServer(
              serverId, displayName, description, tags, status, securityPolicy, tools));
    }

    return new McpCatalogResponse(new ArrayList<>(servers.values()));
  }

  private Map<String, List<McpCatalogResponse.McpTool>> collectToolsByServer() {
    List<ToolDefinition> definitions = toolDefinitionRepository.findAllBySchemaVersionIsNotNull();
    Set<String> availableToolNames = lookupAvailableToolNames();

    Map<String, List<McpCatalogResponse.McpTool>> result = new LinkedHashMap<>();
    for (ToolDefinition definition : definitions) {
      ToolSchemaVersion schema = definition.getSchemaVersion();
      if (schema == null) {
        continue;
      }
      String serverId = safeLowercase(schema.getMcpServer());
      String toolName = safeTrim(schema.getMcpToolName());
      if (!StringUtils.hasText(serverId) || !StringUtils.hasText(toolName)) {
        continue;
      }

      String sanitizedToolName = sanitize(toolName);
      boolean available =
          StringUtils.hasText(sanitizedToolName) && availableToolNames.contains(sanitizedToolName);
      McpCatalogResponse.McpTool tool =
          new McpCatalogResponse.McpTool(
              safeTrim(definition.getCode()),
              definition.getDisplayName(),
              definition.getDescription(),
              toolName,
              schema.getVersion(),
              available);

      result.computeIfAbsent(serverId, key -> new ArrayList<>()).add(tool);
    }

    result.values().forEach(list -> list.sort((a, b) -> a.displayName().compareToIgnoreCase(b.displayName())));
    return result;
  }

  private Set<String> lookupAvailableToolNames() {
    SyncMcpToolCallbackProvider provider = toolCallbackProvider.getIfAvailable();
    if (provider == null) {
      return Set.of();
    }
    ToolCallback[] callbacks = provider.getToolCallbacks();

    log.info("All tool callback {}", Arrays.stream(callbacks).map(ToolCallback::toString).toList());

    if (callbacks == null || callbacks.length == 0) {
      log.info("No MCP tool callbacks discovered (provider returned empty set)");
      return Set.of();
    }
    List<String> rawNames =
        Arrays.stream(callbacks)
            .filter(Objects::nonNull)
            .map(ToolCallback::getToolDefinition)
            .filter(Objects::nonNull)
            .map(def -> safeTrim(def.name()))
            .filter(StringUtils::hasText)
            .toList();

    Set<String> sanitized =
        rawNames.stream()
            .map(name -> sanitize(name))
            .filter(StringUtils::hasText)
            .collect(Collectors.toSet());

    log.info("Discovered MCP tool callbacks: {}", rawNames);
    log.info("Sanitized MCP tool names: {}", sanitized);

    return sanitized;
  }

  private McpServerStatus deriveStatus(String serverId, List<McpCatalogResponse.McpTool> tools) {
    SyncMcpToolCallbackProvider provider = toolCallbackProvider.getIfAvailable();
    if (provider == null) {
      return McpServerStatus.UNKNOWN;
    }
    if (tools.stream().anyMatch(McpCatalogResponse.McpTool::available)) {
      return McpServerStatus.UP;
    }
    if (tools.isEmpty()) {
      return McpServerStatus.UNKNOWN;
    }
    return McpServerStatus.DOWN;
  }

  private String safeTrim(String value) {
    return value != null ? value.trim() : null;
  }

  private String safeLowercase(String value) {
    return value != null ? value.trim().toLowerCase(Locale.ROOT) : null;
  }

  private String humanizeServerId(String serverId) {
    if (!StringUtils.hasText(serverId)) {
      return "Unknown MCP server";
    }
    String[] parts = serverId.split("[_\\-]");
    return Arrays.stream(parts)
        .filter(StringUtils::hasText)
        .map(part -> part.substring(0, 1).toUpperCase(Locale.ROOT) + part.substring(1))
        .collect(Collectors.joining(" "));
  }
}
