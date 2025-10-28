package com.aiadvent.backend.flow.service;

import com.aiadvent.backend.flow.api.FlowDefinitionPublishRequest;
import com.aiadvent.backend.flow.api.FlowDefinitionRequest;
import com.aiadvent.backend.flow.blueprint.FlowBlueprint;
import com.aiadvent.backend.flow.blueprint.FlowBlueprintSchemaVersion;
import com.aiadvent.backend.flow.domain.FlowDefinition;
import com.aiadvent.backend.flow.domain.FlowDefinitionHistory;
import com.aiadvent.backend.flow.domain.FlowDefinitionStatus;
import com.aiadvent.backend.flow.persistence.FlowDefinitionHistoryRepository;
import com.aiadvent.backend.flow.persistence.FlowDefinitionRepository;
import com.aiadvent.backend.flow.telemetry.ConstructorTelemetryService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

@Service
public class FlowDefinitionService {

  private final FlowDefinitionRepository flowDefinitionRepository;
  private final FlowDefinitionHistoryRepository flowDefinitionHistoryRepository;
  private final FlowBlueprintValidator flowBlueprintValidator;
  private final ObjectMapper objectMapper;
  private final ConstructorTelemetryService constructorTelemetryService;

  public FlowDefinitionService(
      FlowDefinitionRepository flowDefinitionRepository,
      FlowDefinitionHistoryRepository flowDefinitionHistoryRepository,
      FlowBlueprintValidator flowBlueprintValidator,
      ObjectMapper objectMapper,
      ConstructorTelemetryService constructorTelemetryService) {
    this.flowDefinitionRepository = flowDefinitionRepository;
    this.flowDefinitionHistoryRepository = flowDefinitionHistoryRepository;
    this.flowBlueprintValidator = flowBlueprintValidator;
    this.objectMapper = objectMapper;
    this.constructorTelemetryService = constructorTelemetryService;
  }

  @Transactional(readOnly = true)
  public List<FlowDefinition> listDefinitions() {
    return flowDefinitionRepository.findAllByOrderByUpdatedAtDesc();
  }

  @Transactional(readOnly = true)
  public FlowDefinition getDefinition(UUID id) {
    return flowDefinitionRepository
        .findById(id)
        .orElseThrow(
            () ->
                new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "Flow definition not found: " + id));
  }

  @Transactional(readOnly = true)
  public FlowDefinition getActivePublishedDefinition(UUID id) {
    FlowDefinition definition = getDefinition(id);
    if (definition.getStatus() != FlowDefinitionStatus.PUBLISHED || !definition.isActive()) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Flow definition is not active and published: " + id);
    }
    return definition;
  }

  @Transactional(readOnly = true)
  public List<FlowDefinitionHistory> getHistory(UUID id) {
    FlowDefinition definition = getDefinition(id);
    return flowDefinitionHistoryRepository.findByFlowDefinitionOrderByVersionDesc(definition);
  }

  @Transactional
  public FlowDefinition createDefinition(FlowDefinitionRequest request) {
    String actor = request != null ? request.updatedBy() : null;
    try {
      if (!StringUtils.hasText(request.name())) {
        throw new IllegalArgumentException("Flow definition name must not be empty");
      }

      FlowBlueprint originalBlueprint = blueprintFromRequest(request);
      int requestedSchemaVersion = FlowBlueprintSchemaVersion.normalize(originalBlueprint.schemaVersion());
      FlowBlueprint blueprint = upgradeBlueprintToCurrent(originalBlueprint);

      int nextVersion =
          flowDefinitionRepository.findByNameOrderByVersionDesc(request.name()).stream()
              .mapToInt(FlowDefinition::getVersion)
              .findFirst()
              .orElse(0)
              + 1;

      FlowDefinition definition =
          new FlowDefinition(
              request.name(), nextVersion, FlowDefinitionStatus.DRAFT, false, blueprint);
      definition.setDescription(request.description());
      definition.setUpdatedBy(request.updatedBy());

      validateSchemaVersion(requestedSchemaVersion, definition, "create", actor);
      flowBlueprintValidator.validateBlueprintOrThrow(blueprint);

      FlowDefinition saved = flowDefinitionRepository.save(definition);

      if (StringUtils.hasText(request.changeNotes())) {
        flowDefinitionHistoryRepository.save(
            new FlowDefinitionHistory(
                saved,
                saved.getVersion(),
                saved.getStatus(),
                saved.getDefinition(),
                saved.getBlueprintSchemaVersion(),
                request.changeNotes(),
                request.updatedBy()));
      }

      constructorTelemetryService.recordFlowBlueprintSave("create", saved, actor);
      return saved;
    } catch (IllegalArgumentException | IllegalStateException | ResponseStatusException ex) {
      constructorTelemetryService.recordValidationError(
          "flow_blueprint", "create", actor, ex);
      throw ex;
    }
  }

  @Transactional
  public FlowDefinition updateDefinition(UUID id, FlowDefinitionRequest request) {
    String actor = request != null ? request.updatedBy() : null;
    try {
      FlowDefinition definition = getDefinition(id);
      if (definition.getStatus() != FlowDefinitionStatus.DRAFT) {
        throw new IllegalStateException("Only DRAFT definitions can be updated");
      }

      FlowBlueprint originalBlueprint = blueprintFromRequest(request);
      int requestedSchemaVersion = FlowBlueprintSchemaVersion.normalize(originalBlueprint.schemaVersion());
      FlowBlueprint blueprint = upgradeBlueprintToCurrent(originalBlueprint);
      definition.setDefinition(blueprint);
      definition.setDescription(request.description());
      if (StringUtils.hasText(request.updatedBy())) {
        definition.setUpdatedBy(request.updatedBy());
      }

      validateSchemaVersion(requestedSchemaVersion, definition, "update", actor);
      flowBlueprintValidator.validateBlueprintOrThrow(blueprint);

      FlowDefinition saved = flowDefinitionRepository.save(definition);

      if (StringUtils.hasText(request.changeNotes())) {
        flowDefinitionHistoryRepository.save(
            new FlowDefinitionHistory(
                saved,
                saved.getVersion(),
                saved.getStatus(),
                saved.getDefinition(),
                saved.getBlueprintSchemaVersion(),
                request.changeNotes(),
                request.updatedBy()));
      }

      constructorTelemetryService.recordFlowBlueprintSave("update", saved, actor);
      return saved;
    } catch (IllegalArgumentException | IllegalStateException | ResponseStatusException ex) {
      constructorTelemetryService.recordValidationError(
          "flow_blueprint", "update", actor, ex);
      throw ex;
    }
  }

  @Transactional
  public FlowDefinition publishDefinition(UUID id, FlowDefinitionPublishRequest request) {
    String actor = request != null ? request.updatedBy() : null;
    try {
      FlowDefinition definition = getDefinition(id);
      if (definition.getStatus() == FlowDefinitionStatus.PUBLISHED && definition.isActive()) {
        return definition;
      }

      FlowBlueprint existingBlueprint = definition.getDefinition();
      int requestedSchemaVersion = FlowBlueprintSchemaVersion.normalize(existingBlueprint != null ? existingBlueprint.schemaVersion() : null);
      FlowBlueprint upgraded = upgradeBlueprintToCurrent(existingBlueprint);
      if (upgraded != null && upgraded != definition.getDefinition()) {
        definition.setDefinition(upgraded);
      }

      validateSchemaVersion(requestedSchemaVersion, definition, "publish", actor);
      flowBlueprintValidator.validateDefinitionOrThrow(definition);

      if (StringUtils.hasText(request.updatedBy())) {
        definition.setUpdatedBy(request.updatedBy());
      }

      definition.setStatus(FlowDefinitionStatus.PUBLISHED);
      definition.setActive(true);
      definition.setPublishedAt(Instant.now());

      deactivateOtherVersions(definition);

      FlowDefinition saved = flowDefinitionRepository.save(definition);

      flowDefinitionHistoryRepository.save(
          new FlowDefinitionHistory(
              saved,
              saved.getVersion(),
              saved.getStatus(),
              saved.getDefinition(),
              saved.getBlueprintSchemaVersion(),
              request.changeNotes(),
              request.updatedBy()));

      constructorTelemetryService.recordFlowBlueprintSave("publish", saved, actor);
      return saved;
    } catch (IllegalArgumentException | IllegalStateException | ResponseStatusException ex) {
      constructorTelemetryService.recordValidationError(
          "flow_blueprint", "publish", actor, ex);
      throw ex;
    }
  }

  private void deactivateOtherVersions(FlowDefinition definition) {
    flowDefinitionRepository.findByNameOrderByVersionDesc(definition.getName()).stream()
        .filter(other -> !Objects.equals(other.getId(), definition.getId()))
        .filter(FlowDefinition::isActive)
        .forEach(
            other -> {
              other.setActive(false);
              flowDefinitionRepository.save(other);
            });
  }

  private void validateSchemaVersion(
      int schemaVersion, FlowDefinition definition, String stage, String actor) {
    if (schemaVersion < FlowBlueprintSchemaVersion.MIN_SUPPORTED) {
      String message =
          "Flow blueprint schemaVersion must be >= "
              + FlowBlueprintSchemaVersion.MIN_SUPPORTED
              + ", actual="
              + schemaVersion;
      constructorTelemetryService.recordFlowBlueprintWarning(
          "schema_version_invalid", stage, definition, actor, message);
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, message);
    }
    if (schemaVersion > FlowBlueprintSchemaVersion.CURRENT) {
      String message =
          "Flow blueprint schemaVersion "
              + schemaVersion
              + " is not supported. Maximum supported version is "
              + FlowBlueprintSchemaVersion.CURRENT;
      constructorTelemetryService.recordFlowBlueprintWarning(
          "schema_version_unsupported", stage, definition, actor, message);
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, message);
    }
    if (schemaVersion < FlowBlueprintSchemaVersion.CURRENT) {
      constructorTelemetryService.recordFlowBlueprintWarning(
          "schema_version_legacy",
          stage,
          definition,
          actor,
          "Flow blueprint schemaVersion=" + schemaVersion + " is deprecated but still supported");
    }
  }

  private FlowBlueprint upgradeBlueprintToCurrent(FlowBlueprint blueprint) {
    return FlowBlueprintSchemaVersion.upgradeToCurrent(blueprint, objectMapper);
  }

  private FlowBlueprint blueprintFromRequest(FlowDefinitionRequest request) {
    JsonNode body = request.definition();
    if (body == null && request.sourceDefinitionId() != null) {
      FlowDefinition source = getDefinition(request.sourceDefinitionId());
      return source.getDefinition();
    }
    if (body == null) {
      throw new IllegalArgumentException("Flow definition body must not be null");
    }
    try {
      return objectMapper
          .copy()
          .configure(com.fasterxml.jackson.databind.MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS, true)
          .treeToValue(body, FlowBlueprint.class);
    } catch (JsonProcessingException exception) {
      throw new IllegalArgumentException("Invalid flow blueprint payload: " + exception.getMessage(), exception);
    }
  }

}
