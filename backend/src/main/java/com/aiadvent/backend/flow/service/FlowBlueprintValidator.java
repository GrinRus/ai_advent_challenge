package com.aiadvent.backend.flow.service;

import com.aiadvent.backend.flow.api.FlowValidationIssue;
import com.aiadvent.backend.flow.blueprint.FlowBlueprint;
import com.aiadvent.backend.flow.blueprint.FlowBlueprintCompiler;
import com.aiadvent.backend.flow.config.FlowDefinitionDocument;
import com.aiadvent.backend.flow.config.FlowStepConfig;
import com.aiadvent.backend.flow.domain.AgentDefinition;
import com.aiadvent.backend.flow.domain.AgentVersion;
import com.aiadvent.backend.flow.domain.AgentVersionStatus;
import com.aiadvent.backend.flow.domain.FlowDefinition;
import com.aiadvent.backend.flow.persistence.AgentVersionRepository;
import com.aiadvent.backend.flow.validation.FlowBlueprintParsingException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class FlowBlueprintValidator {

  private static final EnumSet<AgentVersionStatus> VALID_STATUSES =
      EnumSet.of(AgentVersionStatus.PUBLISHED);

  private final FlowBlueprintCompiler flowBlueprintCompiler;
  private final AgentVersionRepository agentVersionRepository;

  public FlowBlueprintValidator(
      FlowBlueprintCompiler flowBlueprintCompiler, AgentVersionRepository agentVersionRepository) {
    this.flowBlueprintCompiler = flowBlueprintCompiler;
    this.agentVersionRepository = agentVersionRepository;
  }

  public FlowDefinitionDocument validateDefinitionOrThrow(FlowDefinition definition) {
    if (definition == null) {
      throw new IllegalArgumentException("Flow definition must not be null");
    }
    FlowDefinitionDocument document;
    try {
      document = flowBlueprintCompiler.compile(definition);
    } catch (FlowBlueprintParsingException parsingException) {
      throw parsingException(parsingException);
    }
    validateAgentVersionsOrThrow(document);
    return document;
  }

  public FlowDefinitionDocument validateBlueprintOrThrow(FlowBlueprint blueprint) {
    if (blueprint == null) {
      throw new IllegalArgumentException("Flow blueprint must not be null");
    }
    FlowDefinitionDocument document;
    try {
      document = flowBlueprintCompiler.compile(blueprint);
    } catch (FlowBlueprintParsingException parsingException) {
      throw parsingException(parsingException);
    }
    validateAgentVersionsOrThrow(document);
    return document;
  }

  public FlowBlueprintValidationResult validateBlueprint(FlowBlueprint blueprint) {
    if (blueprint == null) {
      return new FlowBlueprintValidationResult(null,
          List.of(new FlowValidationIssue("BLUEPRINT_MISSING", "Blueprint must be provided", null, null)),
          List.of());
    }

    FlowDefinitionDocument document;
    try {
      document = flowBlueprintCompiler.compile(blueprint);
    } catch (FlowBlueprintParsingException parsingException) {
      return new FlowBlueprintValidationResult(null, parsingException.issues(), List.of());
    } catch (Exception unexpected) {
      return new FlowBlueprintValidationResult(
          null,
          List.of(
              new FlowValidationIssue(
                  "BLUEPRINT_ERROR", unexpected.getMessage(), null, null)),
          List.of());
    }

    List<FlowValidationIssue> agentIssues = validateAgentVersions(document, false);
    return new FlowBlueprintValidationResult(document, agentIssues, List.of());
  }

  private void validateAgentVersionsOrThrow(FlowDefinitionDocument document) {
    List<FlowValidationIssue> issues = validateAgentVersions(document, true);
    if (!issues.isEmpty()) {
      FlowValidationIssue first = issues.get(0);
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_ENTITY,
          first.message() != null ? first.message() : "Flow blueprint validation failed");
    }
  }

  private ResponseStatusException parsingException(FlowBlueprintParsingException exception) {
    FlowValidationIssue first =
        exception.issues().isEmpty() ? null : exception.issues().get(0);
    String message =
        first != null && first.message() != null
            ? first.message()
            : "Flow blueprint parsing failed";
    return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, message);
  }

  private List<FlowValidationIssue> validateAgentVersions(
      FlowDefinitionDocument document, boolean failFast) {
    if (document == null) {
      return List.of();
    }

    List<FlowStepConfig> steps = document.steps();
    if (steps.isEmpty()) {
      return List.of(
          new FlowValidationIssue(
              "STEP_MISSING", "Flow blueprint must define at least one step", "/steps", null));
    }

    Set<UUID> agentVersionIds =
        steps.stream()
            .map(FlowStepConfig::agentVersionId)
            .filter(Objects::nonNull)
            .collect(Collectors.toCollection(LinkedHashSet::new));

    if (agentVersionIds.isEmpty()) {
      return List.of(
          new FlowValidationIssue(
              "AGENT_VERSION_MISSING", "Each step must reference agentVersionId", "/steps", null));
    }

    Map<UUID, AgentVersion> versionsById = new HashMap<>();
    agentVersionRepository.findAllById(agentVersionIds).forEach(v -> versionsById.put(v.getId(), v));

    List<FlowValidationIssue> issues = new ArrayList<>();
    for (FlowStepConfig step : steps) {
      UUID agentVersionId = step.agentVersionId();
      if (agentVersionId == null) {
        issues.add(
            new FlowValidationIssue(
                "AGENT_VERSION_MISSING",
                "Step must reference agentVersionId",
                pathForStep(step.id(), "agentVersionId"),
                step.id()));
        if (failFast) {
          break;
        }
        continue;
      }

      AgentVersion version = versionsById.get(agentVersionId);
      if (version == null) {
        issues.add(
            new FlowValidationIssue(
                "AGENT_VERSION_NOT_FOUND",
                "Agent version not found for step '%s': %s".formatted(step.id(), agentVersionId),
                pathForStep(step.id(), "agentVersionId"),
                step.id()));
        if (failFast) {
          break;
        }
        continue;
      }

      if (!VALID_STATUSES.contains(version.getStatus())) {
        issues.add(
            new FlowValidationIssue(
                "AGENT_VERSION_NOT_PUBLISHED",
                "Agent version for step '%s' must be published".formatted(step.id()),
                pathForStep(step.id(), "agentVersionId"),
                step.id()));
        if (failFast) {
          break;
        }
      }

      AgentDefinition definition = version.getAgentDefinition();
      if (definition == null || !definition.isActive()) {
        issues.add(
            new FlowValidationIssue(
                "AGENT_DEFINITION_INACTIVE",
                "Agent definition used in step '%s' is not active".formatted(step.id()),
                pathForStep(step.id(), "agentVersionId"),
                step.id()));
        if (failFast) {
          break;
        }
      }
    }

    return issues;
  }

  private String pathForStep(String stepId, String field) {
    if (stepId == null) {
      return field != null ? "/steps/*/" + field : "/steps/*";
    }
    return field != null ? "/steps/" + stepId + "/" + field : "/steps/" + stepId;
  }
}
