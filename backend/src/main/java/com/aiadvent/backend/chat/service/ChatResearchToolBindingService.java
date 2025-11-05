package com.aiadvent.backend.chat.service;

import com.aiadvent.backend.chat.api.ChatInteractionMode;
import com.aiadvent.backend.chat.config.ChatResearchProperties;
import com.aiadvent.backend.chat.config.ChatResearchProperties.ToolBindingProperties;
import com.aiadvent.backend.flow.agent.options.AgentInvocationOptions;
import com.aiadvent.backend.flow.tool.service.McpToolBindingService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;

@Service
public class ChatResearchToolBindingService {

  private final McpToolBindingService mcpToolBindingService;
  private final ObjectMapper objectMapper;
  private final Map<String, AgentInvocationOptions.ToolBinding> configuredBindings;
  private final String systemPrompt;
  private final String structuredAdvice;
  private final boolean hasConfiguredPrompt;

  public ChatResearchToolBindingService(
      McpToolBindingService mcpToolBindingService,
      ObjectMapper objectMapper,
      ChatResearchProperties researchProperties) {
    this.mcpToolBindingService = mcpToolBindingService;
    this.objectMapper = objectMapper;
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
    if (configuredBindings.isEmpty()) {
      return List.of();
    }
    return List.copyOf(configuredBindings.keySet());
  }

  public ResearchContext resolve(ChatInteractionMode mode, String userQuery) {
    return resolve(mode, userQuery, null);
  }

  public ResearchContext resolve(
      ChatInteractionMode mode, String userQuery, List<String> requestedToolCodes) {
    if (!hasText(userQuery)) {
      return ResearchContext.empty();
    }
    List<String> selectionCodes = normalizeRequestedToolCodes(requestedToolCodes);
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
            bindings, userQuery, selectionCodes, Collections.emptyMap());

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

  private static List<String> normalizeRequestedToolCodes(List<String> codes) {
    if (codes == null || codes.isEmpty()) {
      return List.of();
    }
    LinkedHashSet<String> normalized = new LinkedHashSet<>();
    for (String code : codes) {
      String normalizedCode = normalizeCode(code);
      if (hasText(normalizedCode)) {
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
}
