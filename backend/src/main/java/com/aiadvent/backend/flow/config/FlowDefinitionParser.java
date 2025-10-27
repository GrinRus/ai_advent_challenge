package com.aiadvent.backend.flow.config;

import static com.aiadvent.backend.flow.memory.FlowMemoryChannels.CONVERSATION;
import static com.aiadvent.backend.flow.memory.FlowMemoryChannels.SHARED;

import com.aiadvent.backend.chat.provider.model.ChatRequestOverrides;
import com.aiadvent.backend.flow.blueprint.FlowBlueprint;
import com.aiadvent.backend.flow.blueprint.FlowBlueprintStep;
import com.aiadvent.backend.flow.domain.FlowDefinition;
import com.aiadvent.backend.flow.domain.FlowInteractionType;
import com.aiadvent.backend.flow.validation.FlowInteractionSchemaValidator;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class FlowDefinitionParser {

  private final FlowInteractionSchemaValidator schemaValidator;

  public FlowDefinitionParser(FlowInteractionSchemaValidator schemaValidator) {
    this.schemaValidator = schemaValidator;
  }

  public FlowDefinitionDocument parse(FlowDefinition definition) {
    FlowBlueprint blueprint = definition.getDefinition();
    if (blueprint == null) {
      throw new IllegalArgumentException("Flow blueprint is missing for flow " + definition.getId());
    }
    return parseBlueprint(blueprint);
  }

  public FlowDefinitionDocument parseBlueprint(FlowBlueprint blueprint) {
    Map<String, FlowStepConfig> steps = new LinkedHashMap<>();
    for (FlowBlueprintStep step : blueprint.steps()) {
      FlowStepConfig normalized = parseStep(step);
      if (steps.put(normalized.id(), normalized) != null) {
        throw new IllegalArgumentException("Duplicate step id: " + step.id());
      }
    }

    if (steps.isEmpty()) {
      throw new IllegalArgumentException("Flow definition contains no steps");
    }

    return new FlowDefinitionDocument(blueprint.startStepId(), steps);
  }

  private FlowStepConfig parseStep(FlowBlueprintStep step) {
    if (step == null) {
      throw new IllegalArgumentException("Step entry must not be null");
    }
    String id = step.id();
    if (id == null || id.isBlank()) {
      throw new IllegalArgumentException("Step entry must define id");
    }
    UUID agentVersionId;
    try {
      agentVersionId = UUID.fromString(step.agentVersionId());
    } catch (Exception exception) {
      throw new IllegalArgumentException("Invalid agentVersionId for step " + id, exception);
    }

    ChatRequestOverrides overrides = parseOverrides(step.overrides());
    FlowInteractionConfig interaction = parseInteraction(step.interaction());
    List<MemoryReadConfig> reads = applyDefaultMemoryReads(parseMemoryReads(step.memoryReads()));
    List<MemoryWriteConfig> writes = applyDefaultMemoryWrites(parseMemoryWrites(step.memoryWrites()));
    FlowStepTransitions transitions = parseTransitions(step.transitions());
    int maxAttempts = step.maxAttempts() != null && step.maxAttempts() > 0 ? step.maxAttempts() : 1;

    return new FlowStepConfig(
        id,
        step.name(),
        agentVersionId,
        step.prompt(),
        overrides,
        interaction,
        reads,
        writes,
        transitions,
        maxAttempts);
  }

  private List<MemoryReadConfig> applyDefaultMemoryReads(List<MemoryReadConfig> reads) {
    boolean hasShared =
        reads.stream().anyMatch(read -> SHARED.equalsIgnoreCase(read.channel()));
    boolean hasConversation =
        reads.stream().anyMatch(read -> CONVERSATION.equalsIgnoreCase(read.channel()));
    if (hasShared && hasConversation) {
      return reads;
    }
    List<MemoryReadConfig> augmented = new java.util.ArrayList<>(reads);
    if (!hasShared) {
      augmented.add(new MemoryReadConfig(SHARED, 5));
    }
    if (!hasConversation) {
      augmented.add(new MemoryReadConfig(CONVERSATION, 10));
    }
    return List.copyOf(augmented);
  }

  private List<MemoryWriteConfig> applyDefaultMemoryWrites(List<MemoryWriteConfig> writes) {
    boolean hasShared =
        writes.stream().anyMatch(write -> SHARED.equalsIgnoreCase(write.channel()));
    if (hasShared) {
      return writes;
    }
    List<MemoryWriteConfig> augmented = new java.util.ArrayList<>(writes);
    augmented.add(new MemoryWriteConfig(SHARED, MemoryWriteMode.AGENT_OUTPUT, null));
    return List.copyOf(augmented);
  }

  private ChatRequestOverrides parseOverrides(JsonNode node) {
    if (node == null || node.isNull()) {
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

  private FlowInteractionConfig parseInteraction(JsonNode node) {
    if (node == null || node.isNull()) {
      return null;
    }
    String typeRaw = textValue(node, "type");
    FlowInteractionType type = FlowInteractionType.INPUT_FORM;
    if (typeRaw != null && !typeRaw.isBlank()) {
      try {
        type = FlowInteractionType.valueOf(typeRaw.toUpperCase());
      } catch (IllegalArgumentException exception) {
        throw new IllegalArgumentException("Unsupported interaction type: " + typeRaw, exception);
      }
    }

    String title = textValue(node, "title");
    String description = textValue(node, "description");
    JsonNode payloadSchema = node.get("payloadSchema");
    schemaValidator.validateSchema(payloadSchema);
    JsonNode suggestedActions = node.get("suggestedActions");
    Integer dueInMinutes = null;
    if (node.hasNonNull("dueInMinutes")) {
      dueInMinutes = node.get("dueInMinutes").asInt();
      if (dueInMinutes <= 0) {
        throw new IllegalArgumentException("dueInMinutes must be positive when specified");
      }
    }

    return new FlowInteractionConfig(type, title, description, payloadSchema, suggestedActions, dueInMinutes);
  }

  private List<MemoryReadConfig> parseMemoryReads(JsonNode node) {
    if (node == null || node.isNull()) {
      return List.of();
    }
    if (!node.isArray()) {
      throw new IllegalArgumentException("memoryReads must be an array when provided");
    }
    List<MemoryReadConfig> reads = new java.util.ArrayList<>();
    for (JsonNode item : node) {
      String channel = textValue(item, "channel");
      int limit = item.hasNonNull("limit") ? item.get("limit").asInt() : 10;
      reads.add(new MemoryReadConfig(channel, limit));
    }
    return List.copyOf(reads);
  }

  private List<MemoryWriteConfig> parseMemoryWrites(JsonNode node) {
    if (node == null || node.isNull()) {
      return List.of();
    }
    if (!node.isArray()) {
      throw new IllegalArgumentException("memoryWrites must be an array when provided");
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
    if (node == null || node.isNull()) {
      return FlowStepTransitions.defaults();
    }
    String onSuccess = null;
    boolean completeOnSuccess = false;
    JsonNode successNode = node.get("onSuccess");
    if (successNode != null && !successNode.isNull()) {
      onSuccess = textValue(successNode, "next");
      completeOnSuccess = successNode.path("complete").asBoolean(false);
    }

    String onFailure = null;
    boolean failOnFailure = true;
    JsonNode failureNode = node.get("onFailure");
    if (failureNode != null && !failureNode.isNull()) {
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
}
