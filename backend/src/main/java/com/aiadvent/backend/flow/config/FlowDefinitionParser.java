package com.aiadvent.backend.flow.config;

import static com.aiadvent.backend.flow.memory.FlowMemoryChannels.CONVERSATION;
import static com.aiadvent.backend.flow.memory.FlowMemoryChannels.SHARED;

import com.aiadvent.backend.chat.provider.model.ChatRequestOverrides;
import com.aiadvent.backend.flow.blueprint.FlowBlueprint;
import com.aiadvent.backend.flow.api.FlowValidationIssue;
import com.aiadvent.backend.flow.blueprint.FlowBlueprintMemory;
import com.aiadvent.backend.flow.blueprint.FlowBlueprintMemoryChannel;
import com.aiadvent.backend.flow.blueprint.FlowBlueprintStep;
import com.aiadvent.backend.flow.blueprint.FlowInteractionDraft;
import com.aiadvent.backend.flow.blueprint.FlowMemoryReadDraft;
import com.aiadvent.backend.flow.blueprint.FlowMemoryWriteDraft;
import com.aiadvent.backend.flow.blueprint.FlowStepOverrides;
import com.aiadvent.backend.flow.blueprint.FlowStepTransitionsDraft;
import com.aiadvent.backend.flow.validation.FlowBlueprintIssueCodes;
import com.aiadvent.backend.flow.validation.FlowBlueprintParsingException;
import com.aiadvent.backend.flow.domain.FlowDefinition;
import com.aiadvent.backend.flow.domain.FlowInteractionType;
import com.aiadvent.backend.flow.validation.FlowInteractionSchemaValidator;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
        throw FlowBlueprintParsingException.single(
            issue(
                FlowBlueprintIssueCodes.STEP_DUPLICATE,
                "Duplicate step id: " + step.id(),
                stepFieldPath(step.id(), null),
                step.id()));
      }
    }

    if (steps.isEmpty()) {
      throw FlowBlueprintParsingException.single(
          issue(
              FlowBlueprintIssueCodes.BLUEPRINT_MISSING,
              "Flow definition contains no steps",
              "/steps",
              null));
    }

    FlowMemoryConfig memoryConfig = parseMemoryConfig(blueprint.memory());

    return new FlowDefinitionDocument(blueprint.startStepId(), steps, memoryConfig);
  }

  private FlowStepConfig parseStep(FlowBlueprintStep step) {
    if (step == null) {
      throw FlowBlueprintParsingException.single(
          issue(FlowBlueprintIssueCodes.STEP_NULL, "Step entry must not be null", "/steps", null));
    }
    String id = step.id();
    if (id == null || id.isBlank()) {
      throw FlowBlueprintParsingException.single(
          issue(FlowBlueprintIssueCodes.STEP_ID_MISSING, "Step entry must define id", "/steps/*/id", null));
    }
    UUID agentVersionId;
    try {
      agentVersionId = UUID.fromString(step.agentVersionId());
    } catch (Exception exception) {
      throw FlowBlueprintParsingException.single(
          issue(
              FlowBlueprintIssueCodes.AGENT_VERSION_FORMAT_INVALID,
              "Invalid agentVersionId for step '" + id + "'",
              stepFieldPath(id, "agentVersionId"),
              id));
    }

    ChatRequestOverrides overrides = parseOverrides(step.overrides());
    FlowInteractionConfig interaction = parseInteraction(step.interaction(), id);
    List<MemoryReadConfig> reads = applyDefaultMemoryReads(parseMemoryReads(step.memoryReads(), id));
    List<MemoryWriteConfig> writes = applyDefaultMemoryWrites(parseMemoryWrites(step.memoryWrites(), id));
    FlowStepTransitions transitions = parseTransitions(step.transitions(), id);
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

  private ChatRequestOverrides parseOverrides(FlowStepOverrides overrides) {
    if (overrides == null || overrides.isEmpty()) {
      return ChatRequestOverrides.empty();
    }
    Double temperature = overrides.temperature();
    Double topP = overrides.topP();
    Integer maxTokens = overrides.maxTokens();
    if (temperature == null && topP == null && maxTokens == null) {
      return ChatRequestOverrides.empty();
    }
    return new ChatRequestOverrides(temperature, topP, maxTokens);
  }

  private FlowInteractionConfig parseInteraction(FlowInteractionDraft draft, String stepId) {
    if (draft == null) {
      return null;
    }
    String typeRaw = normalize(draft.type());
    FlowInteractionType type = FlowInteractionType.INPUT_FORM;
    if (typeRaw != null) {
      try {
        type = FlowInteractionType.valueOf(typeRaw.toUpperCase());
      } catch (IllegalArgumentException exception) {
        throw FlowBlueprintParsingException.single(
            issue(
                FlowBlueprintIssueCodes.INTERACTION_TYPE_UNSUPPORTED,
                "Unsupported interaction type: " + typeRaw,
                stepFieldPath(stepId, "interaction/type"),
                stepId));
      }
    }

    String title = normalize(draft.title());
    String description = normalize(draft.description());
    JsonNode payloadSchema = draft.payloadSchema();
    try {
      schemaValidator.validateSchema(payloadSchema);
    } catch (IllegalArgumentException exception) {
      throw FlowBlueprintParsingException.single(
          issue(
              FlowBlueprintIssueCodes.INTERACTION_SCHEMA_INVALID,
              exception.getMessage(),
              stepFieldPath(stepId, "interaction/payloadSchema"),
              stepId));
    }
    JsonNode suggestedActions = draft.suggestedActions();
    Integer dueInMinutes = null;
    if (draft.dueInMinutes() != null) {
      dueInMinutes = draft.dueInMinutes();
      if (dueInMinutes <= 0) {
        throw FlowBlueprintParsingException.single(
            issue(
                FlowBlueprintIssueCodes.INTERACTION_DUE_INVALID,
                "dueInMinutes must be positive when specified",
                stepFieldPath(stepId, "interaction/dueInMinutes"),
                stepId));
      }
    }

    return new FlowInteractionConfig(type, title, description, payloadSchema, suggestedActions, dueInMinutes);
  }

  private List<MemoryReadConfig> parseMemoryReads(List<FlowMemoryReadDraft> drafts, String stepId) {
    if (drafts == null || drafts.isEmpty()) {
      return List.of();
    }
    List<MemoryReadConfig> reads = new java.util.ArrayList<>();
    for (FlowMemoryReadDraft draft : drafts) {
      if (draft == null) {
        throw FlowBlueprintParsingException.single(
            issue(
                FlowBlueprintIssueCodes.MEMORY_READS_FORMAT_INVALID,
                "memoryReads entry must not be null",
                stepFieldPath(stepId, "memoryReads"),
                stepId));
      }
      String channel = normalize(draft.channel());
      if (channel == null) {
        throw FlowBlueprintParsingException.single(
            issue(
                FlowBlueprintIssueCodes.MEMORY_READS_FORMAT_INVALID,
                "memoryReads entry must define channel",
                stepFieldPath(stepId, "memoryReads"),
                stepId));
      }
      int limit =
          draft.limit() != null && draft.limit() > 0
              ? draft.limit()
              : 10;
      reads.add(new MemoryReadConfig(channel, limit));
    }
    return List.copyOf(reads);
  }

  private List<MemoryWriteConfig> parseMemoryWrites(List<FlowMemoryWriteDraft> drafts, String stepId) {
    if (drafts == null || drafts.isEmpty()) {
      return List.of();
    }
    List<MemoryWriteConfig> writes = new java.util.ArrayList<>();
    for (FlowMemoryWriteDraft draft : drafts) {
      if (draft == null) {
        throw FlowBlueprintParsingException.single(
            issue(
                FlowBlueprintIssueCodes.MEMORY_WRITES_FORMAT_INVALID,
                "memoryWrites entry must not be null",
                stepFieldPath(stepId, "memoryWrites"),
                stepId));
      }
      String channel = normalize(draft.channel());
      if (channel == null) {
        throw FlowBlueprintParsingException.single(
            issue(
                FlowBlueprintIssueCodes.MEMORY_WRITES_FORMAT_INVALID,
                "memoryWrites entry must define channel",
                stepFieldPath(stepId, "memoryWrites"),
                stepId));
      }
      MemoryWriteMode mode = resolveWriteMode(draft.mode(), stepId);
      JsonNode payload = draft.payload();
      writes.add(new MemoryWriteConfig(channel, mode, payload));
    }
    return List.copyOf(writes);
  }

  private FlowStepTransitions parseTransitions(FlowStepTransitionsDraft draft, String stepId) {
    if (draft == null) {
      return FlowStepTransitions.defaults();
    }
    String onSuccess = null;
    boolean completeOnSuccess = false;
    FlowStepTransitionsDraft.Success success = draft.onSuccess();
    if (success != null) {
      onSuccess = normalize(success.next());
      completeOnSuccess = Boolean.TRUE.equals(success.complete());
    }

    String onFailure = null;
    boolean failOnFailure = true;
    FlowStepTransitionsDraft.Failure failure = draft.onFailure();
    if (failure != null) {
      onFailure = normalize(failure.next());
      failOnFailure = failure.fail() == null ? true : failure.fail();
    }

    return new FlowStepTransitions(onSuccess, completeOnSuccess, onFailure, failOnFailure);
  }

  private FlowMemoryConfig parseMemoryConfig(FlowBlueprintMemory memory) {
    Map<String, FlowMemoryChannelConfig> channels = new LinkedHashMap<>();
    registerChannel(channels, FlowMemoryChannelConfig.defaults(CONVERSATION));
    registerChannel(channels, FlowMemoryChannelConfig.defaults(SHARED));

    if (memory != null && memory.sharedChannels() != null) {
      for (FlowBlueprintMemoryChannel channel : memory.sharedChannels()) {
        if (channel == null) {
          throw FlowBlueprintParsingException.single(
              issue(
                  FlowBlueprintIssueCodes.MEMORY_CHANNEL_INVALID,
                  "memory.sharedChannels entry must not be null",
                  "/memory/sharedChannels",
                  null));
        }
        String id = normalize(channel.id());
        if (id == null) {
          throw FlowBlueprintParsingException.single(
              issue(
                  FlowBlueprintIssueCodes.MEMORY_CHANNEL_INVALID,
                  "memory.sharedChannels entry must define id",
                  "/memory/sharedChannels",
                  null));
        }
        int retentionVersions =
            channel.retentionVersions() != null && channel.retentionVersions() > 0
                ? channel.retentionVersions()
                : FlowMemoryChannelConfig.DEFAULT_RETENTION_VERSIONS;
        Duration retentionTtl =
            channel.retentionDays() != null && channel.retentionDays() > 0
                ? Duration.ofDays(channel.retentionDays())
                : FlowMemoryChannelConfig.DEFAULT_RETENTION_TTL;

        registerChannel(
            channels, new FlowMemoryChannelConfig(id, retentionVersions, retentionTtl));
      }
    }

    return new FlowMemoryConfig(List.copyOf(channels.values()));
  }

  private void registerChannel(
      Map<String, FlowMemoryChannelConfig> channels, FlowMemoryChannelConfig config) {
    String key = normalize(config.channel());
    if (key == null) {
      throw FlowBlueprintParsingException.single(
          issue(
              FlowBlueprintIssueCodes.MEMORY_CHANNEL_INVALID,
              "Memory channel identifier must not be blank",
              "/memory/sharedChannels",
              null));
    }
    key = key.toLowerCase(Locale.ROOT);
    FlowMemoryChannelConfig existing = channels.get(key);
    if (existing != null) {
      channels.put(key, existing.merge(config));
    } else {
      channels.put(key, config);
    }
  }

  private MemoryWriteMode resolveWriteMode(String rawMode, String stepId) {
    if (rawMode == null || rawMode.isBlank()) {
      return MemoryWriteMode.AGENT_OUTPUT;
    }
    try {
      return MemoryWriteMode.valueOf(rawMode.trim().toUpperCase());
    } catch (IllegalArgumentException exception) {
      throw FlowBlueprintParsingException.single(
          issue(
              FlowBlueprintIssueCodes.MEMORY_WRITES_FORMAT_INVALID,
              "Unsupported memory write mode: " + rawMode,
              stepFieldPath(stepId, "memoryWrites"),
              stepId));
    }
  }

  private String normalize(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  private FlowValidationIssue issue(String code, String message, String path, String stepId) {
    return new FlowValidationIssue(code, message, path, stepId);
  }

  private String stepFieldPath(String stepId, String field) {
    if (stepId == null || stepId.isBlank()) {
      return field != null && !field.isBlank() ? "/steps/*/" + field : "/steps/*";
    }
    if (field == null || field.isBlank()) {
      return "/steps/" + stepId;
    }
    return "/steps/" + stepId + "/" + field;
  }
}
