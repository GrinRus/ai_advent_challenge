package com.aiadvent.backend.flow.blueprint;

import com.aiadvent.backend.flow.config.FlowDefinitionDocument;
import com.aiadvent.backend.flow.config.FlowDefinitionParser;
import com.aiadvent.backend.flow.config.FlowStepConfig;
import com.aiadvent.backend.flow.config.FlowStepTransitions;
import com.aiadvent.backend.flow.domain.FlowDefinition;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class FlowBlueprintCompiler {

  private final FlowDefinitionParser flowDefinitionParser;
  private final ConcurrentMap<CacheKey, FlowDefinitionDocument> cache = new ConcurrentHashMap<>();

  public FlowBlueprintCompiler(FlowDefinitionParser flowDefinitionParser) {
    this.flowDefinitionParser = flowDefinitionParser;
  }

  public FlowDefinitionDocument compile(FlowDefinition definition) {
    if (definition == null) {
      throw new IllegalArgumentException("Flow definition must not be null");
    }
    FlowBlueprint blueprint = definition.getDefinition();
    if (blueprint == null) {
      throw new IllegalArgumentException(
          "Flow definition '%s' does not contain a blueprint".formatted(definition.getId()));
    }

    UUID definitionId = definition.getId();
    if (definitionId == null) {
      return compile(blueprint);
    }

    CacheKey key = CacheKey.from(definition, blueprint);
    FlowDefinitionDocument compiled =
        cache.computeIfAbsent(key, k -> compileInternal(blueprint));
    evictSupersededEntries(definitionId, key);
    return compiled;
  }

  public FlowDefinitionDocument compile(FlowBlueprint blueprint) {
    if (blueprint == null) {
      throw new IllegalArgumentException("Flow blueprint must not be null");
    }
    return compileInternal(blueprint);
  }

  private FlowDefinitionDocument compileInternal(FlowBlueprint blueprint) {
    FlowDefinitionDocument document = flowDefinitionParser.parseBlueprint(blueprint);
    validateReferences(document);
    return document;
  }

  private void validateReferences(FlowDefinitionDocument document) {
    Set<String> stepIds =
        document.steps().stream().map(FlowStepConfig::id).collect(Collectors.toSet());

    for (FlowStepConfig step : document.steps()) {
      FlowStepTransitions transitions = step.transitions();
      if (transitions == null) {
        continue;
      }
      validateTransitionTarget(stepIds, step.id(), "onSuccess", transitions.onSuccess());
      validateTransitionTarget(stepIds, step.id(), "onFailure", transitions.onFailure());
    }
  }

  private void validateTransitionTarget(
      Set<String> stepIds, String sourceStepId, String transitionName, String targetStepId) {
    if (targetStepId == null || targetStepId.isBlank()) {
      return;
    }
    if (!stepIds.contains(targetStepId)) {
      throw new IllegalArgumentException(
          "Step '%s' references unknown %s step '%s'".formatted(sourceStepId, transitionName, targetStepId));
    }
  }

  private void evictSupersededEntries(UUID definitionId, CacheKey latestKey) {
    cache.keySet().removeIf(
        key -> key.definitionId.equals(definitionId) && !key.equals(latestKey));
  }

  private record CacheKey(UUID definitionId, int version, long updatedAt, int schemaVersion) {
    static CacheKey from(FlowDefinition definition, FlowBlueprint blueprint) {
      UUID id = definition.getId();
      Instant updatedAt = definition.getUpdatedAt();
      return new CacheKey(
          Objects.requireNonNull(id, "Definition id must not be null for caching"),
          definition.getVersion(),
          updatedAt != null ? updatedAt.toEpochMilli() : 0L,
          blueprint.schemaVersion() != null ? blueprint.schemaVersion() : 1);
    }
  }
}

