package com.aiadvent.backend.flow.config;

import com.aiadvent.backend.chat.provider.model.ChatRequestOverrides;
import com.aiadvent.backend.flow.domain.FlowDefinition;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class FlowDefinitionParser {

  public FlowDefinitionParser() {}

  public FlowDefinitionDocument parse(FlowDefinition definition) {
    JsonNode root = definition.getDefinition();
    if (root == null || root.isNull()) {
      throw new IllegalArgumentException("Flow definition JSON is empty for flow " + definition.getId());
    }

    String startStepId = textValue(root, "startStepId");
    JsonNode stepsNode = root.get("steps");
    if (stepsNode == null || !stepsNode.isArray()) {
      throw new IllegalArgumentException("Flow definition must contain array 'steps'");
    }

    Map<String, FlowStepConfig> steps = new LinkedHashMap<>();
    for (JsonNode stepNode : (ArrayNode) stepsNode) {
      FlowStepConfig config = parseStep(stepNode);
      if (steps.put(config.id(), config) != null) {
        throw new IllegalArgumentException("Duplicate step id: " + config.id());
      }
    }

    if (steps.isEmpty()) {
      throw new IllegalArgumentException("Flow definition contains no steps");
    }

    return new FlowDefinitionDocument(startStepId, steps);
  }

  private FlowStepConfig parseStep(JsonNode stepNode) {
    if (stepNode == null || !stepNode.isObject()) {
      throw new IllegalArgumentException("Step entry must be an object");
    }
    String id = textValue(stepNode, "id");
    String name = textValue(stepNode, "name");
    String agentVersionIdRaw = textValue(stepNode, "agentVersionId");
    UUID agentVersionId;
    try {
      agentVersionId = UUID.fromString(agentVersionIdRaw);
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException("Invalid agentVersionId for step " + id, exception);
    }
    String prompt = textValue(stepNode, "prompt");

    ChatRequestOverrides overrides = parseOverrides(stepNode.path("overrides"));
    List<MemoryReadConfig> reads = parseMemoryReads(stepNode.path("memoryReads"));
    List<MemoryWriteConfig> writes = parseMemoryWrites(stepNode.path("memoryWrites"));
    FlowStepTransitions transitions = parseTransitions(stepNode.path("transitions"));
    int maxAttempts = intValue(stepNode, "maxAttempts", 1);

    return new FlowStepConfig(
        id, name, agentVersionId, prompt, overrides, reads, writes, transitions, maxAttempts);
  }

  private ChatRequestOverrides parseOverrides(JsonNode node) {
    if (node == null || node.isNull() || node.isMissingNode()) {
      return ChatRequestOverrides.empty();
    }
    Double temperature = node.hasNonNull("temperature") ? node.get("temperature").asDouble() : null;
    Double topP = node.hasNonNull("topP") ? node.get("topP").asDouble() : null;
    Integer maxTokens = node.hasNonNull("maxTokens") ? node.get("maxTokens").asInt() : null;
    if (temperature == null && topP == null && maxTokens == null) {
      return ChatRequestOverrides.empty();
    }
    return new ChatRequestOverrides(temperature, topP, maxTokens);
  }

  private List<MemoryReadConfig> parseMemoryReads(JsonNode node) {
    if (node == null || !node.isArray()) {
      return List.of();
    }
    List<MemoryReadConfig> reads = new java.util.ArrayList<>();
    for (JsonNode item : node) {
      String channel = textValue(item, "channel");
      int limit = intValue(item, "limit", 10);
      reads.add(new MemoryReadConfig(channel, limit));
    }
    return List.copyOf(reads);
  }

  private List<MemoryWriteConfig> parseMemoryWrites(JsonNode node) {
    if (node == null || !node.isArray()) {
      return List.of();
    }
    List<MemoryWriteConfig> writes = new java.util.ArrayList<>();
    for (JsonNode item : node) {
      String channel = textValue(item, "channel");
      MemoryWriteMode mode =
          item.hasNonNull("mode")
              ? MemoryWriteMode.valueOf(item.get("mode").asText().toUpperCase())
              : MemoryWriteMode.AGENT_OUTPUT;
      JsonNode payload = item.get("payload");
      writes.add(new MemoryWriteConfig(channel, mode, payload));
    }
    return List.copyOf(writes);
  }

  private FlowStepTransitions parseTransitions(JsonNode node) {
    if (node == null || node.isMissingNode() || node.isNull()) {
      return FlowStepTransitions.defaults();
    }
    String onSuccess = null;
    boolean completeOnSuccess = false;
    JsonNode successNode = node.get("onSuccess");
    if (successNode != null) {
      onSuccess = textValue(successNode, "next");
      completeOnSuccess = successNode.path("complete").asBoolean(false);
    }

    String onFailure = null;
    boolean failOnFailure = true;
    JsonNode failureNode = node.get("onFailure");
    if (failureNode != null) {
      onFailure = textValue(failureNode, "next");
      if (failureNode.has("fail")) {
        failOnFailure = failureNode.get("fail").asBoolean(true);
      }
    }

    return new FlowStepTransitions(onSuccess, completeOnSuccess, onFailure, failOnFailure);
  }

  private String textValue(JsonNode node, String fieldName) {
    JsonNode valueNode = node != null ? node.get(fieldName) : null;
    if (valueNode == null || valueNode.isNull() || valueNode.asText().isBlank()) {
      return null;
    }
    return valueNode.asText();
  }

  private int intValue(JsonNode node, String fieldName, int defaultValue) {
    JsonNode valueNode = node != null ? node.get(fieldName) : null;
    return valueNode != null && valueNode.isInt() ? valueNode.asInt() : defaultValue;
  }
}
