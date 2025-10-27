package com.aiadvent.backend.flow.tool.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.util.StringUtils;

/**
 * Wrapper around an MCP {@link ToolCallback} that enforces a specific {@code query}
 * payload and strips localisation fields before delegating the execution.
 */
final class QueryOverridingToolCallback implements ToolCallback {

  private static final Logger log = LoggerFactory.getLogger(QueryOverridingToolCallback.class);

  private final ToolCallback delegate;
  private final ObjectMapper objectMapper;
  private final ObjectNode overrides;
  private final Consumer<ObjectNode> payloadCustomizer;

  QueryOverridingToolCallback(
      ToolCallback delegate,
      ObjectMapper objectMapper,
      JsonNode overrides,
      Consumer<ObjectNode> payloadCustomizer) {
    this.delegate = delegate;
    this.objectMapper = objectMapper;
    this.overrides = normalizeOverrides(overrides);
    this.payloadCustomizer = payloadCustomizer != null ? payloadCustomizer : payload -> {};
  }

  @Override
  public ToolDefinition getToolDefinition() {
    return delegate.getToolDefinition();
  }

  @Override
  public String call(String toolInput) {
    return delegate.call(rewrite(toolInput));
  }

  @Override
  public String call(String toolInput, ToolContext toolContext) {
    return delegate.call(rewrite(toolInput), toolContext);
  }

  private ObjectNode normalizeOverrides(JsonNode overridesNode) {
    ObjectNode normalized = objectMapper.createObjectNode();
    if (overridesNode == null || overridesNode.isNull()) {
      return normalized;
    }

    if (!overridesNode.isObject()) {
      log.warn(
          "Ignoring non-object requestOverrides for MCP tool '{}'",
          delegate.getToolDefinition().name());
      return normalized;
    }

    ObjectNode copy = ((ObjectNode) overridesNode).deepCopy();
    copy.remove("query");
    copy.remove("locale");
    return copy;
  }

  private String rewrite(String toolInput) {
    ObjectNode payload = parsePayload(toolInput);
    if (overrides.size() > 0) {
      payload.setAll(overrides.deepCopy());
    }
    payload.remove("locale");
    payloadCustomizer.accept(payload);
    try {
      return objectMapper.writeValueAsString(payload);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException(
          "Failed to serialize MCP tool payload for '%s'"
              .formatted(delegate.getToolDefinition().name()),
          exception);
    }
  }

  private ObjectNode parsePayload(String toolInput) {
    if (!StringUtils.hasText(toolInput)) {
      return objectMapper.createObjectNode();
    }
    try {
      JsonNode parsed = objectMapper.readTree(toolInput);
      if (parsed != null && parsed.isObject()) {
        ObjectNode copy = ((ObjectNode) parsed).deepCopy();
        copy.remove("locale");
        return copy;
      }
      log.debug(
          "Expected JSON object for MCP tool payload '{}', but received {}. Falling back to empty object.",
          delegate.getToolDefinition().name(),
          parsed != null ? parsed.getNodeType() : "null");
      return objectMapper.createObjectNode();
    } catch (JsonProcessingException exception) {
      log.debug(
          "Failed to parse MCP tool payload for '{}'. Falling back to empty object.",
          delegate.getToolDefinition().name(),
          exception);
      return objectMapper.createObjectNode();
    }
  }
}
