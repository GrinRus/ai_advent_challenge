package com.aiadvent.backend.flow.service;

import com.aiadvent.backend.flow.blueprint.FlowBlueprint;
import com.aiadvent.backend.flow.domain.FlowDefinition;
import com.aiadvent.backend.flow.domain.FlowDefinitionHistory;
import com.aiadvent.backend.flow.persistence.FlowDefinitionHistoryRepository;
import com.aiadvent.backend.flow.persistence.FlowDefinitionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FlowBlueprintMigrationService {

  private static final Logger log = LoggerFactory.getLogger(FlowBlueprintMigrationService.class);

  private final FlowDefinitionRepository flowDefinitionRepository;
  private final FlowDefinitionHistoryRepository flowDefinitionHistoryRepository;
  private final FlowBlueprintValidator flowBlueprintValidator;
  private final ObjectMapper objectMapper;

  public FlowBlueprintMigrationService(
      FlowDefinitionRepository flowDefinitionRepository,
      FlowDefinitionHistoryRepository flowDefinitionHistoryRepository,
      FlowBlueprintValidator flowBlueprintValidator,
      ObjectMapper objectMapper) {
    this.flowDefinitionRepository = flowDefinitionRepository;
    this.flowDefinitionHistoryRepository = flowDefinitionHistoryRepository;
    this.flowBlueprintValidator = flowBlueprintValidator;
    this.objectMapper = objectMapper;
  }

  @Transactional
  public FlowBlueprintMigrationResult migrate(FlowBlueprintMigrationRequest request) {
    List<FlowDefinition> definitions =
        request.definitionIds().isEmpty()
            ? flowDefinitionRepository.findAll()
            : new ArrayList<>(flowDefinitionRepository.findAllById(request.definitionIds()));

    int processedDefinitions = 0;
    int definitionsNeedingUpdate = 0;
    int definitionsUpdated = 0;
    int processedHistoryEntries = 0;
    int historyEntriesNeedingUpdate = 0;
    int historyEntriesUpdated = 0;
    int validationFailures = 0;
    int failures = 0;

    for (FlowDefinition definition : definitions) {
      processedDefinitions++;
      try {
        FlowBlueprint sanitizedBlueprint = sanitizeBlueprint(definition.getDefinition());

        FlowBlueprintValidationResult validationResult =
            flowBlueprintValidator.validateBlueprint(sanitizedBlueprint);
        if (!validationResult.isValid()) {
          validationFailures++;
          log.warn(
              "Blueprint validation failed for flow definition {} (id={}): {}",
              definition.getName(),
              definition.getId(),
              validationResult.errors());
          if (request.failOnError()) {
            throw new IllegalStateException(
                "Blueprint validation failed for flow definition " + definition.getId());
          }
          continue;
        }

        boolean blueprintChanged =
            hasDefinitionChanged(definition.getDefinition(), sanitizedBlueprint);
        int targetSchemaVersion = schemaVersionOrDefault(sanitizedBlueprint);
        boolean schemaChanged = definition.getBlueprintSchemaVersion() != targetSchemaVersion;

        if (blueprintChanged || schemaChanged) {
          definitionsNeedingUpdate++;
          if (!request.dryRun()) {
            definition.setDefinition(sanitizedBlueprint);
            flowDefinitionRepository.save(definition);
            definitionsUpdated++;
            log.info(
                "Updated flow definition '{}' (id={}) to schema version {}",
                definition.getName(),
                definition.getId(),
                targetSchemaVersion);
          } else {
            log.info(
                "[Dry-run] Flow definition '{}' (id={}) requires update to schema version {}",
                definition.getName(),
                definition.getId(),
                targetSchemaVersion);
          }
        }

        if (request.includeHistory()) {
          List<FlowDefinitionHistory> historyEntries =
              flowDefinitionHistoryRepository.findByFlowDefinitionOrderByVersionDesc(definition);
          for (FlowDefinitionHistory history : historyEntries) {
            processedHistoryEntries++;
            FlowBlueprint sanitizedHistory = sanitizeBlueprint(history.getDefinition());
            boolean historyChanged =
                hasDefinitionChanged(history.getDefinition(), sanitizedHistory);
            int historySchemaVersion = schemaVersionOrDefault(sanitizedHistory);
            boolean historySchemaMismatch =
                history.getBlueprintSchemaVersion() != historySchemaVersion;

            if (historyChanged || historySchemaMismatch) {
              historyEntriesNeedingUpdate++;
              if (!request.dryRun()) {
                history.setDefinition(sanitizedHistory);
                flowDefinitionHistoryRepository.save(history);
                historyEntriesUpdated++;
                log.info(
                    "Updated flow definition history record {} for flow '{}' to schema version {}",
                    history.getId(),
                    definition.getName(),
                    historySchemaVersion);
              } else {
                log.info(
                    "[Dry-run] Flow definition history record {} for flow '{}' requires update to schema version {}",
                    history.getId(),
                    definition.getName(),
                    historySchemaVersion);
              }
            }
          }
        }
      } catch (Exception ex) {
        failures++;
        log.error(
            "Failed to migrate flow definition '{}' (id={})",
            definition.getName(),
            definition.getId(),
            ex);
        if (request.failOnError()) {
          if (ex instanceof RuntimeException runtimeException) {
            throw runtimeException;
          }
          throw new IllegalStateException(ex);
        }
      }
    }

    return new FlowBlueprintMigrationResult(
        processedDefinitions,
        definitionsNeedingUpdate,
        definitionsUpdated,
        processedHistoryEntries,
        historyEntriesNeedingUpdate,
        historyEntriesUpdated,
        validationFailures,
        failures);
  }

  private FlowBlueprint sanitizeBlueprint(FlowBlueprint blueprint) {
    if (blueprint == null) {
      throw new IllegalArgumentException("Flow blueprint must not be null");
    }
    JsonNode tree = objectMapper.valueToTree(blueprint);
    return objectMapper.convertValue(tree, FlowBlueprint.class);
  }

  private boolean hasDefinitionChanged(FlowBlueprint original, FlowBlueprint candidate) {
    JsonNode originalNode = objectMapper.valueToTree(original);
    JsonNode candidateNode = objectMapper.valueToTree(candidate);
    return !Objects.equals(originalNode, candidateNode);
  }

  private int schemaVersionOrDefault(FlowBlueprint blueprint) {
    Integer schemaVersion = blueprint.schemaVersion();
    return schemaVersion != null && schemaVersion > 0 ? schemaVersion : 1;
  }

  public record FlowBlueprintMigrationRequest(
      List<UUID> definitionIds, boolean includeHistory, boolean dryRun, boolean failOnError) {

    public FlowBlueprintMigrationRequest {
      definitionIds =
          definitionIds != null ? List.copyOf(definitionIds) : Collections.emptyList();
    }
  }

  public record FlowBlueprintMigrationResult(
      int processedDefinitions,
      int definitionsNeedingUpdate,
      int definitionsUpdated,
      int processedHistoryEntries,
      int historyEntriesNeedingUpdate,
      int historyEntriesUpdated,
      int validationFailures,
      int failures) {}
}
