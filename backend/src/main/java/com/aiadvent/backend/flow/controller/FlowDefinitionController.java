package com.aiadvent.backend.flow.controller;

import com.aiadvent.backend.flow.api.FlowDefinitionHistoryResponse;
import com.aiadvent.backend.flow.api.FlowDefinitionPublishRequest;
import com.aiadvent.backend.flow.api.FlowDefinitionRequest;
import com.aiadvent.backend.flow.api.FlowDefinitionResponse;
import com.aiadvent.backend.flow.api.FlowDefinitionSummaryResponse;
import com.aiadvent.backend.flow.api.FlowLaunchPreviewResponse;
import com.aiadvent.backend.flow.domain.FlowDefinition;
import com.aiadvent.backend.flow.domain.FlowDefinitionHistory;
import com.aiadvent.backend.flow.service.FlowDefinitionService;
import com.aiadvent.backend.flow.service.FlowLaunchPreviewService;
import com.fasterxml.jackson.databind.JsonNode;
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
  private final ObjectMapper objectMapper;

  public FlowDefinitionController(
      FlowDefinitionService flowDefinitionService,
      FlowLaunchPreviewService flowLaunchPreviewService,
      ObjectMapper objectMapper) {
    this.flowDefinitionService = flowDefinitionService;
    this.flowLaunchPreviewService = flowLaunchPreviewService;
    this.objectMapper = objectMapper;
  }

  @GetMapping
  public List<FlowDefinitionSummaryResponse> listDefinitions() {
    return flowDefinitionService.listDefinitions().stream()
        .map(this::toSummary)
        .collect(Collectors.toList());
  }

  @GetMapping("/{id}")
  public FlowDefinitionResponse getDefinition(@PathVariable UUID id) {
    return toResponse(flowDefinitionService.getDefinition(id));
  }

  @GetMapping("/{id}/launch-preview")
  public FlowLaunchPreviewResponse launchPreview(@PathVariable UUID id) {
    return flowLaunchPreviewService.preview(id);
  }

  @GetMapping("/{id}/history")
  public List<FlowDefinitionHistoryResponse> getHistory(@PathVariable UUID id) {
    return flowDefinitionService.getHistory(id).stream()
        .map(this::toHistory)
        .collect(Collectors.toList());
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public FlowDefinitionResponse create(@RequestBody FlowDefinitionRequest request) {
    FlowDefinition created = flowDefinitionService.createDefinition(request);
    return toResponse(created);
  }

  @PutMapping("/{id}")
  public FlowDefinitionResponse update(
      @PathVariable UUID id, @RequestBody FlowDefinitionRequest request) {
    FlowDefinition updated = flowDefinitionService.updateDefinition(id, request);
    return toResponse(updated);
  }

  @PostMapping("/{id}/publish")
  public FlowDefinitionResponse publish(
      @PathVariable UUID id, @RequestBody FlowDefinitionPublishRequest request) {
    FlowDefinition published = flowDefinitionService.publishDefinition(id, request);
    return toResponse(published);
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

  private FlowDefinitionHistoryResponse toHistory(FlowDefinitionHistory history) {
    return new FlowDefinitionHistoryResponse(
        history.getId(),
        history.getVersion(),
        history.getStatus(),
        objectMapper.valueToTree(history.getDefinition()),
        StringUtils.hasText(history.getChangeNotes()) ? history.getChangeNotes() : null,
        history.getCreatedBy(),
        history.getCreatedAt());
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
