package com.aiadvent.backend.flow.tool.service;

import com.aiadvent.backend.chat.logging.ChatLoggingSupport;
import com.aiadvent.backend.flow.agent.options.AgentInvocationOptions;
import com.aiadvent.backend.flow.tool.domain.ToolDefinition;
import com.aiadvent.backend.flow.tool.domain.ToolSchemaVersion;
import com.aiadvent.backend.flow.tool.persistence.ToolDefinitionRepository;
import com.aiadvent.backend.mcp.util.McpToolNameSanitizer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

/**
 * Resolves MCP-backed tool bindings into executable {@link ToolCallback} instances.
 *
 * <p>This service bridges catalogued tool bindings with the active set of tools
 * discovered from configured Model Context Protocol servers.</p>
 */
@Service
public class McpToolBindingService {

  private static final Logger log = LoggerFactory.getLogger(McpToolBindingService.class);

  private static final String HTTP_STREAM_TRANSPORT = "http-stream";
  private static final String STDIO_TRANSPORT = "stdio";

  private final ToolDefinitionRepository toolDefinitionRepository;
  private final ObjectProvider<SyncMcpToolCallbackProvider> toolCallbackProvider;
  private final ObjectMapper objectMapper;
  private final ChatLoggingSupport chatLoggingSupport;
  private final AtomicBoolean missingProviderLogged = new AtomicBoolean(false);

  public record ResolvedTool(String toolCode, ToolCallback callback) {}

  public McpToolBindingService(
      ToolDefinitionRepository toolDefinitionRepository,
      ObjectProvider<SyncMcpToolCallbackProvider> toolCallbackProvider,
      ObjectMapper objectMapper,
      ChatLoggingSupport chatLoggingSupport) {
    this.toolDefinitionRepository = toolDefinitionRepository;
    this.toolCallbackProvider = toolCallbackProvider;
    this.objectMapper = objectMapper;
    this.chatLoggingSupport = chatLoggingSupport;
  }

  /**
   * Resolves MCP tool callbacks for the provided bindings.
   *
   * @param bindings sequence of {@link AgentInvocationOptions.ToolBinding}
   * @param userQuery sanitized user query that should populate the {@code query} field
   * @return ordered list of tool callbacks ready for registration
   */
  public List<ResolvedTool> resolveCallbacks(
      List<AgentInvocationOptions.ToolBinding> bindings,
      String userQuery,
      List<String> requestedToolCodes,
      Map<String, JsonNode> requestOverrides) {

    if (CollectionUtils.isEmpty(bindings) || !StringUtils.hasText(userQuery)) {
      return List.of();
    }

    Set<String> allowedToolCodes = normalizeToolCodes(requestedToolCodes);
    Map<String, JsonNode> overridesByTool = normalizeOverrideMap(requestOverrides);

    Map<String, ResolvedTool> callbacksByName = new LinkedHashMap<>();
    for (AgentInvocationOptions.ToolBinding binding : bindings) {
      createCallback(binding, userQuery, allowedToolCodes, overridesByTool)
          .ifPresent(resolved ->
              callbacksByName.putIfAbsent(resolved.callback().getToolDefinition().name(), resolved));
    }
    return List.copyOf(callbacksByName.values());
  }

  private Optional<ResolvedTool> createCallback(
      AgentInvocationOptions.ToolBinding binding,
      String userQuery,
      Set<String> allowedToolCodes,
      Map<String, JsonNode> requestOverrides) {

    if (binding == null) {
      return Optional.empty();
    }

    String toolCode = binding.toolCode();
    if (!StringUtils.hasText(toolCode)) {
      return Optional.empty();
    }
    String normalizedCode = normalizeToolCode(toolCode);

    boolean hasExplicitSelection = allowedToolCodes != null && !allowedToolCodes.isEmpty();
    boolean isManual = binding.executionMode() == AgentInvocationOptions.ExecutionMode.MANUAL;

    if (hasExplicitSelection && (normalizedCode == null || !allowedToolCodes.contains(normalizedCode))) {
      return Optional.empty();
    }
    if (!hasExplicitSelection && isManual) {
      return Optional.empty();
    }

    Optional<ToolDefinition> definitionOpt =
        toolDefinitionRepository.findByCodeIgnoreCase(toolCode.trim());
    if (definitionOpt.isEmpty()) {
      log.debug("Tool '{}' is not registered in catalog; skipping MCP resolution", toolCode);
      return Optional.empty();
    }

    ToolSchemaVersion schemaVersion = definitionOpt.get().getSchemaVersion();
    if (schemaVersion == null) {
      log.debug(
          "Tool '{}' does not have an attached schema version; skipping MCP resolution", toolCode);
      return Optional.empty();
    }

    String transport = schemaVersion.getTransport();
    if (!StringUtils.hasText(transport)) {
      log.debug(
          "Tool '{}' does not declare an MCP transport; skipping MCP resolution", toolCode);
      return Optional.empty();
    }

    String normalizedTransport = transport.trim().toLowerCase(Locale.ROOT);
    boolean httpTransport = HTTP_STREAM_TRANSPORT.equals(normalizedTransport);
    boolean stdioTransport = STDIO_TRANSPORT.equals(normalizedTransport);

    if (!httpTransport && !stdioTransport) {
      log.debug(
          "Tool '{}' uses unsupported MCP transport '{}'; skipping MCP resolution",
          toolCode,
          transport);
      return Optional.empty();
    }

    String mcpToolName = schemaVersion.getMcpToolName();
    String sanitizedToolName = McpToolNameSanitizer.sanitize(mcpToolName);
    if (!StringUtils.hasText(mcpToolName) || !StringUtils.hasText(sanitizedToolName)) {
      log.debug("Tool '{}' does not expose an MCP tool name; skipping", toolCode);
      return Optional.empty();
    }

    ObjectNode baseOverrides = safeOverrides(binding.requestOverrides());
    JsonNode invocationOverrides = requestOverrides.getOrDefault(normalizedCode, null);
    ObjectNode mergedOverrides = mergeOverrides(baseOverrides, invocationOverrides);

    Consumer<ObjectNode> payloadCustomizer =
        buildPayloadCustomizer(toolCode, mcpToolName, userQuery);
    Optional<ToolCallback> resolvedCallback = resolveToolCallbackByName(sanitizedToolName);
    if (resolvedCallback.isEmpty()) {
      log.warn(
          "MCP tool '{}' (sanitized '{}') is not available among registered callbacks; skipping binding for tool code '{}'",
          mcpToolName,
          sanitizedToolName,
          toolCode);
    }
    return resolvedCallback
        .map(
            delegate ->
                new ResolvedTool(
                    toolCode.trim(),
                    chatLoggingSupport.decorateToolCallback(
                        new QueryOverridingToolCallback(
                            delegate, objectMapper, mergedOverrides, payloadCustomizer))));
  }

  private Consumer<ObjectNode> buildPayloadCustomizer(
      String toolCode, String mcpToolName, String userQuery) {
    String normalizedName =
        mcpToolName != null ? mcpToolName.trim().toLowerCase(Locale.ROOT) : "";
    String normalizedCode = normalizeToolCode(toolCode);
    if (normalizedCode == null) {
      normalizedCode = "";
    }

    if ("perplexity_search".equals(normalizedName)) {
      return payload -> payload.put("query", userQuery);
    }

    if ("perplexity_research".equals(normalizedName)
        || normalizedCode.contains("deep_research")) {
      return payload -> {
        payload.put("query", userQuery);
        JsonNode existing = payload.get("messages");
        ArrayNode messages;
        if (existing != null && existing.isArray()) {
          messages = (ArrayNode) existing;
        } else {
          messages = objectMapper.createArrayNode();
          payload.set("messages", messages);
        }
        boolean alreadyPresent = false;
        for (JsonNode message : messages) {
          if (message.isObject()
              && userQuery.equals(message.path("content").asText(null))) {
            alreadyPresent = true;
            break;
          }
        }
        if (!alreadyPresent) {
          ObjectNode messageNode = objectMapper.createObjectNode();
          messageNode.put("role", "user");
          messageNode.put("content", userQuery);
          messages.add(messageNode);
        }
      };
    }

    if ("insight.search_memory".equals(normalizedName)
        || normalizedCode.contains("insight_search_memory")) {
      return payload -> payload.put("query", userQuery);
    }

    return payload -> {};
  }

  private ObjectNode safeOverrides(JsonNode overrides) {
    if (overrides == null || overrides.isNull()) {
      return objectMapper.createObjectNode();
    }
    if (overrides.isObject()) {
      return ((ObjectNode) overrides).deepCopy();
    }
    return objectMapper.createObjectNode();
  }

  private ObjectNode mergeOverrides(ObjectNode base, JsonNode additional) {
    ObjectNode merged = base != null ? base.deepCopy() : objectMapper.createObjectNode();
    if (additional != null && additional.isObject()) {
      merged.setAll((ObjectNode) additional);
    }
    return merged;
  }

  private Set<String> normalizeToolCodes(List<String> codes) {
    if (codes == null || codes.isEmpty()) {
      return null;
    }
    Set<String> normalized = new LinkedHashSet<>();
    for (String code : codes) {
      String normalizedCode = normalizeToolCode(code);
      if (normalizedCode != null) {
        normalized.add(normalizedCode);
      }
    }
    return normalized.isEmpty() ? null : normalized;
  }

  private Map<String, JsonNode> normalizeOverrideMap(Map<String, JsonNode> overrides) {
    if (overrides == null || overrides.isEmpty()) {
      return Collections.emptyMap();
    }
    Map<String, JsonNode> normalized = new LinkedHashMap<>();
    overrides.forEach(
        (code, value) -> {
          String normalizedCode = normalizeToolCode(code);
          if (normalizedCode != null && value != null && !value.isNull()) {
            normalized.put(normalizedCode, value);
          }
        });
    return normalized;
  }

  private String normalizeToolCode(String toolCode) {
    if (!StringUtils.hasText(toolCode)) {
      return null;
    }
    return toolCode.trim().toLowerCase(Locale.ROOT);
  }

  private Optional<ToolCallback> resolveToolCallbackByName(String sanitizedToolName) {
    SyncMcpToolCallbackProvider provider = toolCallbackProvider.getIfAvailable();
    if (provider == null) {
      if (missingProviderLogged.compareAndSet(false, true)) {
        log.warn(
            "MCP tool callback provider is not available; MCP tools are disabled until the provider is configured");
      }
      return Optional.empty();
    }

    ToolCallback[] callbacks = provider.getToolCallbacks();

    log.debug("Get all tool callbacks {}", Arrays.toString(callbacks));

    missingProviderLogged.set(false);
    Optional<ToolCallback> resolved = Arrays.stream(callbacks)
        .filter(Objects::nonNull)
        .filter(
            callback ->
                sanitizedToolName.equals(
                    McpToolNameSanitizer.sanitize(callback.getToolDefinition().name())))
        .findFirst();
    if (resolved.isEmpty() && log.isDebugEnabled()) {
      Set<String> knownNames =
          Arrays.stream(callbacks)
              .filter(Objects::nonNull)
              .map(callback -> McpToolNameSanitizer.sanitize(callback.getToolDefinition().name()))
              .filter(Objects::nonNull)
              .collect(Collectors.toCollection(LinkedHashSet::new));
      log.debug(
          "Known sanitized MCP tool names from provider: {}", knownNames);
    }
    return resolved;
  }
}
