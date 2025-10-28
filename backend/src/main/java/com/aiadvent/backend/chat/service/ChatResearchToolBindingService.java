package com.aiadvent.backend.chat.service;

import com.aiadvent.backend.chat.api.ChatInteractionMode;
import com.aiadvent.backend.chat.config.ChatResearchProperties;
import com.aiadvent.backend.chat.config.ChatResearchProperties.ToolBindingProperties;
import com.aiadvent.backend.flow.agent.options.AgentInvocationOptions;
import com.aiadvent.backend.flow.tool.service.McpToolBindingService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;

@Service
public class ChatResearchToolBindingService {

  private final McpToolBindingService mcpToolBindingService;
  private final ObjectMapper objectMapper;
  private final List<AgentInvocationOptions.ToolBinding> researchBindings;
  private final List<String> defaultRequestedToolCodes;
  private final String systemPrompt;
  private final String structuredAdvice;
  private final boolean hasConfiguredPrompt;

  public ChatResearchToolBindingService(
      McpToolBindingService mcpToolBindingService,
      ObjectMapper objectMapper,
      ChatResearchProperties researchProperties) {
    this.mcpToolBindingService = mcpToolBindingService;
    this.objectMapper = objectMapper;
    List<AgentInvocationOptions.ToolBinding> bindingsFromConfig =
        researchProperties.getTools().isEmpty()
            ? List.of(buildDefaultBinding())
            : researchProperties.getTools().stream()
                .map(this::toBinding)
                .filter(binding -> hasText(binding.toolCode()))
                .toList();

    this.researchBindings = bindingsFromConfig;

    List<String> configuredDefaults =
        researchProperties.getDefaultToolCodes().isEmpty()
            ? bindingsFromConfig.stream()
                .filter(binding -> binding.executionMode() != AgentInvocationOptions.ExecutionMode.MANUAL)
                .map(binding -> normalizeCode(binding.toolCode()))
                .filter(ChatResearchToolBindingService::hasText)
                .toList()
            : researchProperties.getDefaultToolCodes().stream()
                .map(ChatResearchToolBindingService::normalizeCode)
                .filter(ChatResearchToolBindingService::hasText)
                .toList();

    this.defaultRequestedToolCodes = List.copyOf(configuredDefaults);
    this.systemPrompt = researchProperties.getSystemPrompt();
    this.structuredAdvice = researchProperties.getStructuredAdvice();
    this.hasConfiguredPrompt = hasText(systemPrompt) || hasText(structuredAdvice);
  }

  public ResearchContext resolve(ChatInteractionMode mode, String userQuery) {
    return resolve(mode, userQuery, null);
  }

  public ResearchContext resolve(
      ChatInteractionMode mode, String userQuery, List<String> requestedToolCodes) {
    if (!mode.isResearch() || !hasText(userQuery)) {
      return ResearchContext.empty();
    }
    List<String> normalizedRequested = normalizeRequestedToolCodes(requestedToolCodes);
    List<String> selectionCodes =
        !normalizedRequested.isEmpty() ? normalizedRequested : defaultRequestedToolCodes;

    List<McpToolBindingService.ResolvedTool> resolvedTools =
        mcpToolBindingService.resolveCallbacks(
            researchBindings, userQuery, selectionCodes, Collections.emptyMap());

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

    String prompt = hasConfiguredPrompt ? systemPrompt : null;
    String advice = hasConfiguredPrompt ? structuredAdvice : null;
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

  private AgentInvocationOptions.ToolBinding buildDefaultBinding() {
    ObjectNode overrides = objectMapper.createObjectNode();
    overrides.put("max_results", 8);
    return new AgentInvocationOptions.ToolBinding(
        "perplexity_search",
        1,
        AgentInvocationOptions.ExecutionMode.AUTO,
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
    return codes.stream()
        .map(ChatResearchToolBindingService::normalizeCode)
        .filter(ChatResearchToolBindingService::hasText)
        .toList();
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
