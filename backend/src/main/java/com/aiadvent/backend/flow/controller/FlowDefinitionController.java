package com.aiadvent.backend.flow.controller;

import com.aiadvent.backend.flow.api.FlowDefinitionHistoryResponse;
import com.aiadvent.backend.flow.api.FlowDefinitionPublishRequest;
import com.aiadvent.backend.flow.api.FlowDefinitionRequest;
import com.aiadvent.backend.flow.api.FlowDefinitionResponse;
import com.aiadvent.backend.flow.api.FlowDefinitionResponseV2;
import com.aiadvent.backend.flow.api.FlowDefinitionSummaryResponse;
import com.aiadvent.backend.flow.api.FlowInteractionReferenceResponse;
import com.aiadvent.backend.flow.api.FlowLaunchPreviewResponse;
import com.aiadvent.backend.flow.api.FlowLaunchPreviewResponseV2;
import com.aiadvent.backend.flow.api.FlowMemoryReferenceResponse;
import com.aiadvent.backend.flow.api.FlowStepValidationRequest;
import com.aiadvent.backend.flow.api.FlowStepValidationResponse;
import com.aiadvent.backend.flow.api.FlowValidationIssue;
import com.aiadvent.backend.flow.domain.FlowDefinition;
import com.aiadvent.backend.flow.domain.FlowDefinitionHistory;
import com.aiadvent.backend.flow.domain.FlowInteractionType;
import com.aiadvent.backend.flow.memory.FlowMemoryChannels;
import com.aiadvent.backend.flow.config.FlowApiProperties;
import com.aiadvent.backend.flow.service.FlowBlueprintValidationResult;
import com.aiadvent.backend.flow.service.FlowBlueprintValidator;
import com.aiadvent.backend.flow.service.FlowDefinitionService;
import com.aiadvent.backend.flow.service.FlowLaunchPreviewPayload;
import com.aiadvent.backend.flow.service.FlowLaunchPreviewService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/flows/definitions")
public class FlowDefinitionController {

  private final FlowDefinitionService flowDefinitionService;
  private final FlowLaunchPreviewService flowLaunchPreviewService;
  private final FlowBlueprintValidator flowBlueprintValidator;
  private final FlowApiProperties flowApiProperties;
  private final ObjectMapper objectMapper;

  public FlowDefinitionController(
      FlowDefinitionService flowDefinitionService,
      FlowLaunchPreviewService flowLaunchPreviewService,
      FlowBlueprintValidator flowBlueprintValidator,
      FlowApiProperties flowApiProperties,
      ObjectMapper objectMapper) {
    this.flowDefinitionService = flowDefinitionService;
    this.flowLaunchPreviewService = flowLaunchPreviewService;
    this.flowBlueprintValidator = flowBlueprintValidator;
    this.flowApiProperties = flowApiProperties;
    this.objectMapper = objectMapper;
  }

  @GetMapping
  public List<FlowDefinitionSummaryResponse> listDefinitions() {
    return flowDefinitionService.listDefinitions().stream()
        .map(this::toSummary)
        .collect(Collectors.toList());
  }

  @GetMapping("/{id}")
  public Object getDefinition(@PathVariable UUID id) {
    FlowDefinition definition = flowDefinitionService.getDefinition(id);
    return flowApiProperties.isV2Enabled() ? toResponseV2(definition) : toResponse(definition);
  }

  @GetMapping("/{id}/launch-preview")
  public Object launchPreview(@PathVariable UUID id) {
    FlowLaunchPreviewPayload payload = flowLaunchPreviewService.preview(id);
    return flowApiProperties.isV2Enabled()
        ? toPreviewResponseV2(payload)
        : toPreviewResponse(payload);
  }

  @GetMapping("/{id}/history")
  public List<FlowDefinitionHistoryResponse> getHistory(@PathVariable UUID id) {
    return flowDefinitionService.getHistory(id).stream()
        .map(this::toHistory)
        .collect(Collectors.toList());
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public Object create(@RequestBody FlowDefinitionRequest request) {
    FlowDefinition created = flowDefinitionService.createDefinition(request);
    return flowApiProperties.isV2Enabled() ? toResponseV2(created) : toResponse(created);
  }

  @PutMapping("/{id}")
  public Object update(
      @PathVariable UUID id, @RequestBody FlowDefinitionRequest request) {
    FlowDefinition updated = flowDefinitionService.updateDefinition(id, request);
    return flowApiProperties.isV2Enabled() ? toResponseV2(updated) : toResponse(updated);
  }

  @PostMapping("/{id}/publish")
  public Object publish(
      @PathVariable UUID id, @RequestBody FlowDefinitionPublishRequest request) {
    FlowDefinition published = flowDefinitionService.publishDefinition(id, request);
    return flowApiProperties.isV2Enabled() ? toResponseV2(published) : toResponse(published);
  }

  @GetMapping("/reference/memory-channels")
  public FlowMemoryReferenceResponse memoryReference() {
    return new FlowMemoryReferenceResponse(
        List.of(
            new FlowMemoryReferenceResponse.MemoryChannel(
                FlowMemoryChannels.CONVERSATION,
                "Primary conversation transcript channel (always present).",
                true),
            new FlowMemoryReferenceResponse.MemoryChannel(
                FlowMemoryChannels.SHARED,
                "Shared long-lived context across flow steps.",
                false)));
  }

  @GetMapping("/reference/interaction-schemes")
  public FlowInteractionReferenceResponse interactionReference() {
    return new FlowInteractionReferenceResponse(
        List.of(
            new FlowInteractionReferenceResponse.InteractionScheme(
                FlowInteractionType.INPUT_FORM,
                "Input form",
                "Collect structured payload from operator via JSON schema.",
                "{\"type\":\"object\",\"properties\":{\"answer\":{\"type\":\"string\"}},\"required\":[\"answer\"]}"),
            new FlowInteractionReferenceResponse.InteractionScheme(
                FlowInteractionType.APPROVAL,
                "Approval",
                "Operator approves or rejects step outcome with optional comment.",
                "{\"type\":\"object\",\"properties\":{\"approved\":{\"type\":\"boolean\"},\"comment\":{\"type\":\"string\"}},\"required\":[\"approved\"]}"),
            new FlowInteractionReferenceResponse.InteractionScheme(
                FlowInteractionType.CONFIRMATION,
                "Confirmation",
                "Quick acknowledgement with optional note.",
                "{\"type\":\"object\",\"properties\":{\"confirmed\":{\"type\":\"boolean\"},\"note\":{\"type\":\"string\"}},\"required\":[\"confirmed\"]}"),
            new FlowInteractionReferenceResponse.InteractionScheme(
                FlowInteractionType.REVIEW,
                "Review",
                "Request structured review with decision and notes.",
                "{\"type\":\"object\",\"properties\":{\"decision\":{\"type\":\"string\",\"enum\":[\"approve\",\"revise\",\"reject\"]},\"notes\":{\"type\":\"string\"}},\"required\":[\"decision\"]}"),
            new FlowInteractionReferenceResponse.InteractionScheme(
                FlowInteractionType.INFORMATION,
                "Information",
                "Display informational payload to operator (no response required).",
                "{\"type\":\"object\",\"properties\":{\"message\":{\"type\":\"string\"}}}")));
  }

  @PostMapping("/validation/step")
  public FlowStepValidationResponse validateStep(@RequestBody FlowStepValidationRequest request) {
    FlowBlueprintValidationResult result = flowBlueprintValidator.validateBlueprint(request.blueprint());

    List<FlowValidationIssue> errors = filterIssues(result.errors(), request.stepId());
    List<FlowValidationIssue> warnings = filterIssues(result.warnings(), request.stepId());

    if (result.document() != null && StringUtils.hasText(request.stepId())) {
      try {
        result.document().step(request.stepId());
      } catch (IllegalArgumentException exception) {
        errors = appendIssue(
            errors,
            new FlowValidationIssue(
                "STEP_NOT_FOUND",
                "Step '%s' is not present in blueprint".formatted(request.stepId()),
                "/steps/" + request.stepId(),
                request.stepId()));
      }
    }

    return FlowStepValidationResponse.withIssues(errors, warnings);
  }

  private FlowDefinitionSummaryResponse toSummary(FlowDefinition definition) {
    return new FlowDefinitionSummaryResponse(
        definition.getId(),
        definition.getName(),
        definition.getVersion(),
        definition.getStatus(),
        definition.isActive(),
        definition.getDescription(),
        definition.getUpdatedBy(),
        definition.getUpdatedAt(),
        definition.getPublishedAt());
  }

  private FlowDefinitionResponse toResponse(FlowDefinition definition) {
    JsonNode definitionNode = sanitizeDefinition(objectMapper.valueToTree(definition.getDefinition()));
    return new FlowDefinitionResponse(
        definition.getId(),
        definition.getName(),
        definition.getVersion(),
        definition.getStatus(),
        definition.isActive(),
        definitionNode,
        definition.getDescription(),
        definition.getUpdatedBy(),
        definition.getCreatedAt(),
        definition.getUpdatedAt(),
        definition.getPublishedAt());
  }

  private FlowDefinitionResponseV2 toResponseV2(FlowDefinition definition) {
    return new FlowDefinitionResponseV2(
        definition.getId(),
        definition.getName(),
        definition.getVersion(),
        definition.getStatus(),
        definition.isActive(),
        definition.getDefinition(),
        definition.getDescription(),
        definition.getUpdatedBy(),
        definition.getCreatedAt(),
        definition.getUpdatedAt(),
        definition.getPublishedAt());
  }

  private FlowDefinitionHistoryResponse toHistory(FlowDefinitionHistory history) {
    return new FlowDefinitionHistoryResponse(
        history.getId(),
        history.getVersion(),
        history.getStatus(),
        objectMapper.valueToTree(history.getDefinition()),
        history.getBlueprintSchemaVersion(),
        StringUtils.hasText(history.getChangeNotes()) ? history.getChangeNotes() : null,
        history.getCreatedBy(),
        history.getCreatedAt());
  }

  private FlowLaunchPreviewResponse toPreviewResponse(FlowLaunchPreviewPayload payload) {
    return new FlowLaunchPreviewResponse(
        payload.definitionId(),
        payload.definitionName(),
        payload.definitionVersion(),
        payload.description(),
        payload.startStepId(),
        List.copyOf(payload.steps()),
        payload.totalEstimate());
  }

  private FlowLaunchPreviewResponseV2 toPreviewResponseV2(FlowLaunchPreviewPayload payload) {
    return new FlowLaunchPreviewResponseV2(
        payload.definitionId(),
        payload.definitionName(),
        payload.definitionVersion(),
        payload.description(),
        payload.blueprint(),
        payload.startStepId(),
        List.copyOf(payload.steps()),
        payload.totalEstimate());
  }

  private List<FlowValidationIssue> filterIssues(List<FlowValidationIssue> issues, String stepId) {
    if (issues == null || issues.isEmpty()) {
      return List.of();
    }
    if (!StringUtils.hasText(stepId)) {
      return List.copyOf(issues);
    }
    return issues.stream()
        .filter(issue -> issue == null || issue.stepId() == null || stepId.equals(issue.stepId()))
        .toList();
  }

  private List<FlowValidationIssue> appendIssue(
      List<FlowValidationIssue> issues, FlowValidationIssue additional) {
    if (additional == null) {
      return issues != null ? List.copyOf(issues) : List.of();
    }
    java.util.ArrayList<FlowValidationIssue> mutable =
        issues != null ? new java.util.ArrayList<>(issues) : new java.util.ArrayList<>();
    mutable.add(additional);
    return List.copyOf(mutable);
  }

  private JsonNode sanitizeDefinition(JsonNode node) {
    if (!(node instanceof ObjectNode objectNode)) {
      return node;
    }

    if (!objectNode.hasNonNull("title")) {
      objectNode.put("title", "");
    }

    JsonNode stepsNode = objectNode.get("steps");
    if (stepsNode instanceof ArrayNode stepsArray) {
      for (JsonNode stepNode : stepsArray) {
        if (stepNode instanceof ObjectNode stepObject) {
          JsonNode overridesNode = stepObject.get("overrides");
          if (!(overridesNode instanceof ObjectNode)) {
            stepObject.set("overrides", objectMapper.createObjectNode());
          }
        }
      }
    }

    return objectNode;
  }
}
