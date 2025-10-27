package com.aiadvent.backend.chat.service;

import com.aiadvent.backend.chat.api.ChatInteractionMode;
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

  private static final String RESEARCH_SYSTEM_PROMPT =
      "You are a meticulous research analyst. Use the Perplexity MCP tooling to collect current information, enrich answers with concise insights, and always cite numbered sources like [1] referencing the returned tool results.";

  private static final String RESEARCH_STRUCTURED_ADVICE =
      """
      При необходимости вызывай инструменты MCP Perplexity, чтобы получить актуальные данные. \
      Включай ссылки на источники в поле `details`, оформляя их в формате [n], где n соответствует номеру источника. \
      Если инструмент возвращает несколько ссылок, добавь краткое пояснение, чему соответствует каждая из них.
      """;

  private final McpToolBindingService mcpToolBindingService;
  private final ObjectMapper objectMapper;
  private final List<AgentInvocationOptions.ToolBinding> researchBindings;

  public ChatResearchToolBindingService(
      McpToolBindingService mcpToolBindingService, ObjectMapper objectMapper) {
    this.mcpToolBindingService = mcpToolBindingService;
    this.objectMapper = objectMapper;
    this.researchBindings = List.of(buildSearchBinding());
  }

  public ResearchContext resolve(ChatInteractionMode mode, String userQuery) {
    if (!mode.isResearch() || !hasText(userQuery)) {
      return ResearchContext.empty();
    }
    List<McpToolBindingService.ResolvedTool> resolvedTools =
        mcpToolBindingService.resolveCallbacks(
            researchBindings, userQuery, List.of("perplexity_search"), Collections.emptyMap());

    if (resolvedTools.isEmpty()) {
      return ResearchContext.empty();
    }

    List<ToolCallback> callbacks =
        resolvedTools.stream().map(McpToolBindingService.ResolvedTool::callback).toList();
    List<String> toolCodes =
        resolvedTools.stream()
            .map(McpToolBindingService.ResolvedTool::toolCode)
            .map(code -> code != null ? code.toLowerCase(Locale.ROOT) : null)
            .filter(ChatResearchToolBindingService::hasText)
            .toList();

    return new ResearchContext(RESEARCH_SYSTEM_PROMPT, RESEARCH_STRUCTURED_ADVICE, callbacks, toolCodes);
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

  private AgentInvocationOptions.ToolBinding buildSearchBinding() {
    ObjectNode overrides = objectMapper.createObjectNode();
    overrides.put("max_results", 8);
    return new AgentInvocationOptions.ToolBinding(
        "perplexity_search",
        1,
        AgentInvocationOptions.ExecutionMode.AUTO,
        overrides,
        null);
  }

  private static boolean hasText(String value) {
    return value != null && !value.trim().isEmpty();
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
