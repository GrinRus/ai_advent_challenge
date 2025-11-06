package com.aiadvent.backend.chat.service;

import com.aiadvent.backend.chat.api.ChatInteractionMode;
import com.aiadvent.backend.chat.config.ChatResearchProperties;
import com.aiadvent.backend.chat.config.ChatResearchProperties.ToolBindingProperties;
import com.aiadvent.backend.flow.agent.options.AgentInvocationOptions;
import com.aiadvent.backend.flow.tool.domain.ToolDefinition;
import com.aiadvent.backend.flow.tool.persistence.ToolDefinitionRepository;
import com.aiadvent.backend.flow.tool.domain.ToolSchemaVersion;
import com.aiadvent.backend.flow.tool.service.McpToolBindingService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;

@Service
public class ChatResearchToolBindingService {

  private final McpToolBindingService mcpToolBindingService;
  private final ObjectMapper objectMapper;
  private final ToolDefinitionRepository toolDefinitionRepository;
  private final Map<String, AgentInvocationOptions.ToolBinding> configuredBindings;
  private final String systemPrompt;
  private final String structuredAdvice;
  private final boolean hasConfiguredPrompt;
  private final List<String> disabledToolNamespaces;

  public ChatResearchToolBindingService(
      McpToolBindingService mcpToolBindingService,
      ObjectMapper objectMapper,
      ChatResearchProperties researchProperties,
      ToolDefinitionRepository toolDefinitionRepository) {
    this.mcpToolBindingService = mcpToolBindingService;
    this.objectMapper = objectMapper;
    this.toolDefinitionRepository = toolDefinitionRepository;
    this.disabledToolNamespaces = researchProperties.getDisabledToolNamespaces();
    Map<String, AgentInvocationOptions.ToolBinding> configured = new LinkedHashMap<>();
    for (ToolBindingProperties properties : researchProperties.getTools()) {
      AgentInvocationOptions.ToolBinding binding = toBinding(properties);
      String normalizedCode = normalizeCode(binding.toolCode());
      if (hasText(normalizedCode)) {
        configured.put(normalizedCode, binding);
      }
    }
    this.configuredBindings = configured.isEmpty() ? Map.of() : Map.copyOf(configured);
    this.systemPrompt = researchProperties.getSystemPrompt();
    this.structuredAdvice = researchProperties.getStructuredAdvice();
    this.hasConfiguredPrompt = hasText(systemPrompt) || hasText(structuredAdvice);
  }

  public List<String> availableToolCodes() {
    LinkedHashSet<String> codes = new LinkedHashSet<>();
    if (!configuredBindings.isEmpty()) {
      configuredBindings.keySet().stream().filter(this::isAllowedToolCode).forEach(codes::add);
    }
    if (toolDefinitionRepository != null) {
      toolDefinitionRepository.findAllBySchemaVersionIsNotNull().stream()
          .map(ToolDefinition::getCode)
          .map(ChatResearchToolBindingService::normalizeCode)
          .filter(this::isAllowedToolCode)
          .sorted(Comparator.naturalOrder())
          .forEach(codes::add);
    }
    return codes.isEmpty() ? List.of() : List.copyOf(codes);
  }

  public Map<String, List<String>> availableToolNamespaces() {
    Map<String, LinkedHashSet<String>> grouped = new TreeMap<>();

    if (toolDefinitionRepository != null) {
      toolDefinitionRepository.findAllBySchemaVersionIsNotNull().stream()
          .forEach(
              definition -> {
                String toolCode = normalizeCode(definition.getCode());
                if (!isAllowedToolCode(toolCode)) {
                  return;
                }
                ToolSchemaVersion schemaVersion = definition.getSchemaVersion();
                String namespace = normalizeNamespace(schemaVersion);
                if (!hasText(namespace)) {
                  namespace = deriveNamespace(toolCode);
                }
                if (!hasText(namespace) || isDisabledToolCode(namespace)) {
                  return;
                }
                grouped.computeIfAbsent(namespace, key -> new LinkedHashSet<>()).add(toolCode);
              });
    }

    if (!configuredBindings.isEmpty()) {
      configuredBindings
          .values()
          .forEach(
              binding -> {
                String toolCode = normalizeCode(binding.toolCode());
                if (!isAllowedToolCode(toolCode)) {
                  return;
                }
                String namespace = deriveNamespace(toolCode);
                if (!hasText(namespace) || isDisabledToolCode(namespace)) {
                  return;
                }
                grouped.computeIfAbsent(namespace, key -> new LinkedHashSet<>()).add(toolCode);
              });
    }

    if (grouped.isEmpty()) {
      return Map.of();
    }

    Map<String, List<String>> result = new LinkedHashMap<>();
    grouped.forEach(
        (namespace, codes) -> result.put(namespace, List.copyOf(codes)));
    return Map.copyOf(result);
  }

  public List<String> resolveToolCodesForNamespaces(List<String> namespaces) {
    if (namespaces == null || namespaces.isEmpty()) {
      return List.of();
    }
    Map<String, List<String>> available = availableToolNamespaces();
    if (available.isEmpty()) {
      return List.of();
    }
    LinkedHashSet<String> resolved = new LinkedHashSet<>();
    for (String namespace : namespaces) {
      String normalized = normalizeCode(namespace);
      if (!hasText(normalized) || isDisabledToolCode(normalized)) {
        continue;
      }
      List<String> codes = available.get(normalized);
      if (codes != null && !codes.isEmpty()) {
        resolved.addAll(codes);
      }
    }
    return resolved.isEmpty() ? List.of() : List.copyOf(resolved);
  }

  public ResearchContext resolve(ChatInteractionMode mode, String userQuery) {
    return resolve(mode, userQuery, null, Map.of());
  }

  public ResearchContext resolve(
      ChatInteractionMode mode, String userQuery, List<String> requestedToolCodes) {
    return resolve(mode, userQuery, requestedToolCodes, Map.of());
  }

  public ResearchContext resolve(
      ChatInteractionMode mode,
      String userQuery,
      List<String> requestedToolCodes,
      Map<String, JsonNode> requestOverrides) {
    if (!hasText(userQuery)) {
      return ResearchContext.empty();
    }
    List<String> selectionCodes =
        normalizeRequestedToolCodes(requestedToolCodes, disabledToolNamespaces);
    if (selectionCodes.isEmpty()) {
      return ResearchContext.empty();
    }

    List<AgentInvocationOptions.ToolBinding> bindings = new ArrayList<>(selectionCodes.size());
    for (String code : selectionCodes) {
      AgentInvocationOptions.ToolBinding binding = configuredBindings.get(code);
      if (binding == null) {
        binding = buildBinding(code);
      }
      bindings.add(binding);
    }

    List<McpToolBindingService.ResolvedTool> resolvedTools =
        mcpToolBindingService.resolveCallbacks(
            bindings, userQuery, selectionCodes, normalizeOverrideMap(requestOverrides));

    if (resolvedTools.isEmpty()) {
      return ResearchContext.empty();
    }

    List<ToolCallback> callbacks =
        resolvedTools.stream().map(McpToolBindingService.ResolvedTool::callback).toList();
    List<String> toolCodes =
        resolvedTools.stream()
            .map(McpToolBindingService.ResolvedTool::toolCode)
            .map(ChatResearchToolBindingService::normalizeCode)
            .filter(ChatResearchToolBindingService::hasText)
            .toList();

    boolean researchMode = mode != null && mode.isResearch();
    String prompt = researchMode && hasConfiguredPrompt ? systemPrompt : null;
    String advice = researchMode && hasConfiguredPrompt ? structuredAdvice : null;
    return new ResearchContext(prompt, advice, callbacks, toolCodes);
  }

  public Optional<JsonNode> tryParseStructuredPayload(ChatInteractionMode mode, String content) {
    if (!mode.isResearch() || !hasText(content)) {
      return Optional.empty();
    }
    try {
      JsonNode node = objectMapper.readTree(content);
      if (node != null && !node.isNull() && !node.isMissingNode()) {
        return Optional.of(node);
      }
    } catch (Exception ignored) {
      // fall back to plain text if parsing fails
    }
    return Optional.empty();
  }

  private AgentInvocationOptions.ToolBinding buildBinding(String code) {
    ObjectNode overrides = objectMapper.createObjectNode();
    return new AgentInvocationOptions.ToolBinding(
        code,
        null,
        AgentInvocationOptions.ExecutionMode.MANUAL,
        overrides,
        null);
  }

  private AgentInvocationOptions.ToolBinding toBinding(ToolBindingProperties properties) {
    if (!hasText(properties.code())) {
      return new AgentInvocationOptions.ToolBinding(
          null, 0, properties.executionMode(), objectMapper.createObjectNode(), null);
    }
    JsonNode overridesNode = properties.requestOverrides();
    ObjectNode overrides;
    if (overridesNode == null || overridesNode.isNull()) {
      overrides = objectMapper.createObjectNode();
    } else if (overridesNode instanceof ObjectNode objectNode) {
      overrides = objectNode.deepCopy();
    } else {
      overrides = objectMapper.convertValue(overridesNode, ObjectNode.class);
    }
    return new AgentInvocationOptions.ToolBinding(
        properties.code(),
        properties.schemaVersion(),
        properties.executionMode(),
        overrides,
        null);
  }

  private static boolean hasText(String value) {
    return value != null && !value.trim().isEmpty();
  }

  private static String normalizeCode(String code) {
    return hasText(code) ? code.trim().toLowerCase(Locale.ROOT) : null;
  }

  private static List<String> normalizeRequestedToolCodes(
      List<String> codes, List<String> disabledNamespaces) {
    if (codes == null || codes.isEmpty()) {
      return List.of();
    }
    LinkedHashSet<String> normalized = new LinkedHashSet<>();
    for (String code : codes) {
      String normalizedCode = normalizeCode(code);
      if (hasText(normalizedCode) && !isDisabledToolCode(normalizedCode, disabledNamespaces)) {
        normalized.add(normalizedCode);
      }
    }
    return normalized.isEmpty() ? List.of() : List.copyOf(normalized);
  }

  public record ResearchContext(
      String systemPrompt, String structuredAdvice, List<ToolCallback> callbacks, List<String> toolCodes) {
    private static final ResearchContext EMPTY =
        new ResearchContext(null, null, List.of(), List.of());

    public static ResearchContext empty() {
      return EMPTY;
    }

    public boolean hasCallbacks() {
      return callbacks != null && !callbacks.isEmpty();
    }

    public boolean hasSystemPrompt() {
      return hasText(systemPrompt);
    }

    public boolean hasStructuredAdvice() {
      return hasText(structuredAdvice);
    }
  }

  private boolean isDisabledToolCode(String code) {
    return isDisabledToolCode(code, disabledToolNamespaces);
  }

  private static boolean isDisabledToolCode(String code, List<String> disabledNamespaces) {
    if (!hasText(code) || disabledNamespaces == null || disabledNamespaces.isEmpty()) {
      return false;
    }
    for (String namespace : disabledNamespaces) {
      if (matchesNamespace(code, namespace)) {
        return true;
      }
    }
    return false;
  }

  private static boolean matchesNamespace(String code, String namespace) {
    if (!hasText(namespace)) {
      return false;
    }
    if (code.equals(namespace)) {
      return true;
    }
    if (code.startsWith(namespace)) {
      int length = namespace.length();
      if (code.length() == length) {
        return true;
      }
      char separator = code.charAt(length);
      return separator == '.' || separator == '_' || separator == ':';
    }
    return false;
  }

  private boolean isAllowedToolCode(String code) {
    return hasText(code) && !isDisabledToolCode(code);
  }

  private String deriveNamespace(String toolCode) {
    if (!hasText(toolCode)) {
      return null;
    }
    String normalized = toolCode.trim().toLowerCase(Locale.ROOT);
    int dotIndex = normalized.indexOf('.');
    if (dotIndex > 0) {
      return normalized.substring(0, dotIndex);
    }
    int underscoreIndex = normalized.indexOf('_');
    if (underscoreIndex > 0) {
      return normalized.substring(0, underscoreIndex);
    }
    return normalized;
  }

  private String normalizeNamespace(ToolSchemaVersion schemaVersion) {
    if (schemaVersion == null) {
      return null;
    }
    return normalizeNamespace(schemaVersion.getMcpServer());
  }

  private String normalizeNamespace(String namespace) {
    return hasText(namespace) ? namespace.trim().toLowerCase(Locale.ROOT) : null;
  }

  private Map<String, JsonNode> normalizeOverrideMap(Map<String, JsonNode> overrides) {
    if (overrides == null || overrides.isEmpty()) {
      return Map.of();
    }
    Map<String, JsonNode> normalized = new LinkedHashMap<>();
    overrides.forEach(
        (code, node) -> {
          String normalizedCode = normalizeCode(code);
          if (hasText(normalizedCode) && node != null && !node.isNull()) {
            normalized.put(normalizedCode, node);
          }
        });
    return normalized.isEmpty() ? Map.of() : Collections.unmodifiableMap(normalized);
  }
}
