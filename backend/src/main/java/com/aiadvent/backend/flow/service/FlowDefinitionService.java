package com.aiadvent.backend.flow.service;

import com.aiadvent.backend.flow.api.FlowDefinitionPublishRequest;
import com.aiadvent.backend.flow.api.FlowDefinitionRequest;
import com.aiadvent.backend.flow.blueprint.FlowBlueprint;
import com.aiadvent.backend.flow.config.FlowDefinitionDocument;
import com.aiadvent.backend.flow.config.FlowDefinitionParser;
import com.aiadvent.backend.flow.config.FlowStepConfig;
import com.aiadvent.backend.flow.domain.AgentDefinition;
import com.aiadvent.backend.flow.domain.AgentVersion;
import com.aiadvent.backend.flow.domain.AgentVersionStatus;
import com.aiadvent.backend.flow.domain.FlowDefinition;
import com.aiadvent.backend.flow.domain.FlowDefinitionHistory;
import com.aiadvent.backend.flow.domain.FlowDefinitionStatus;
import com.aiadvent.backend.flow.persistence.AgentVersionRepository;
import com.aiadvent.backend.flow.persistence.FlowDefinitionHistoryRepository;
import com.aiadvent.backend.flow.persistence.FlowDefinitionRepository;
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
  private final FlowDefinitionParser flowDefinitionParser;
  private final AgentVersionRepository agentVersionRepository;
  private final ObjectMapper objectMapper;

  public FlowDefinitionService(
      FlowDefinitionRepository flowDefinitionRepository,
      FlowDefinitionHistoryRepository flowDefinitionHistoryRepository,
      FlowDefinitionParser flowDefinitionParser,
      AgentVersionRepository agentVersionRepository,
      ObjectMapper objectMapper) {
    this.flowDefinitionRepository = flowDefinitionRepository;
    this.flowDefinitionHistoryRepository = flowDefinitionHistoryRepository;
    this.flowDefinitionParser = flowDefinitionParser;
    this.agentVersionRepository = agentVersionRepository;
    this.objectMapper = objectMapper;
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
    if (!StringUtils.hasText(request.name())) {
      throw new IllegalArgumentException("Flow definition name must not be empty");
    }

    FlowBlueprint blueprint = blueprintFromRequest(request);

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

    FlowDefinitionDocument document = flowDefinitionParser.parse(definition);
    validateAgentVersions(document);

    FlowDefinition saved = flowDefinitionRepository.save(definition);

    if (StringUtils.hasText(request.changeNotes())) {
      flowDefinitionHistoryRepository.save(
          new FlowDefinitionHistory(
              saved,
              saved.getVersion(),
              saved.getStatus(),
              saved.getDefinition(),
              request.changeNotes(),
              request.updatedBy()));
    }

    return saved;
  }

  @Transactional
  public FlowDefinition updateDefinition(UUID id, FlowDefinitionRequest request) {
    FlowDefinition definition = getDefinition(id);
    if (definition.getStatus() != FlowDefinitionStatus.DRAFT) {
      throw new IllegalStateException("Only DRAFT definitions can be updated");
    }

    FlowBlueprint blueprint = blueprintFromRequest(request);
    definition.setDefinition(blueprint);
    definition.setDescription(request.description());
    if (StringUtils.hasText(request.updatedBy())) {
      definition.setUpdatedBy(request.updatedBy());
    }

    FlowDefinitionDocument document = flowDefinitionParser.parse(definition);
    validateAgentVersions(document);

    FlowDefinition saved = flowDefinitionRepository.save(definition);

    if (StringUtils.hasText(request.changeNotes())) {
      flowDefinitionHistoryRepository.save(
          new FlowDefinitionHistory(
              saved,
              saved.getVersion(),
              saved.getStatus(),
              saved.getDefinition(),
              request.changeNotes(),
              request.updatedBy()));
    }

    return saved;
  }

  @Transactional
  public FlowDefinition publishDefinition(UUID id, FlowDefinitionPublishRequest request) {
    FlowDefinition definition = getDefinition(id);
    if (definition.getStatus() == FlowDefinitionStatus.PUBLISHED && definition.isActive()) {
      return definition;
    }

    FlowDefinitionDocument document = flowDefinitionParser.parse(definition);
    validateAgentVersions(document);

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
            request.changeNotes(),
            request.updatedBy()));

    return saved;
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

  private void validateAgentVersions(FlowDefinitionDocument document) {
    for (FlowStepConfig step : document.steps()) {
      UUID agentVersionId = step.agentVersionId();
      AgentVersion agentVersion =
          agentVersionRepository
              .findById(agentVersionId)
              .orElseThrow(
                  () ->
                      new ResponseStatusException(
                          HttpStatus.UNPROCESSABLE_ENTITY,
                          String.format(
                              "Agent version not found for step '%s': %s",
                              step.id(), agentVersionId)));

      if (agentVersion.getStatus() != AgentVersionStatus.PUBLISHED) {
        throw new ResponseStatusException(
            HttpStatus.UNPROCESSABLE_ENTITY,
            String.format(
                "Agent version for step '%s' must be published", step.id()));
      }

      AgentDefinition agentDefinition = agentVersion.getAgentDefinition();
      if (agentDefinition == null || !agentDefinition.isActive()) {
        throw new ResponseStatusException(
            HttpStatus.UNPROCESSABLE_ENTITY,
            String.format(
                "Agent definition used in step '%s' is not active", step.id()));
      }
    }
  }
}
