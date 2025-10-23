package com.aiadvent.backend.flow.controller;

import com.aiadvent.backend.flow.api.FlowDefinitionHistoryResponse;
import com.aiadvent.backend.flow.api.FlowDefinitionPublishRequest;
import com.aiadvent.backend.flow.api.FlowDefinitionRequest;
import com.aiadvent.backend.flow.api.FlowDefinitionResponse;
import com.aiadvent.backend.flow.api.FlowDefinitionSummaryResponse;
import com.aiadvent.backend.flow.domain.FlowDefinition;
import com.aiadvent.backend.flow.domain.FlowDefinitionHistory;
import com.aiadvent.backend.flow.service.FlowDefinitionService;
import com.fasterxml.jackson.databind.JsonNode;
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

  public FlowDefinitionController(FlowDefinitionService flowDefinitionService) {
    this.flowDefinitionService = flowDefinitionService;
  }

  @GetMapping
  public List<FlowDefinitionSummaryResponse> listDefinitions() {
    return flowDefinitionService.listDefinitions().stream()
        .map(FlowDefinitionController::toSummary)
        .collect(Collectors.toList());
  }

  @GetMapping("/{id}")
  public FlowDefinitionResponse getDefinition(@PathVariable UUID id) {
    return toResponse(flowDefinitionService.getDefinition(id));
  }

  @GetMapping("/{id}/history")
  public List<FlowDefinitionHistoryResponse> getHistory(@PathVariable UUID id) {
    return flowDefinitionService.getHistory(id).stream()
        .map(FlowDefinitionController::toHistory)
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

  private static FlowDefinitionSummaryResponse toSummary(FlowDefinition definition) {
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

  private static FlowDefinitionResponse toResponse(FlowDefinition definition) {
    JsonNode definitionNode = definition.getDefinition();
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

  private static FlowDefinitionHistoryResponse toHistory(FlowDefinitionHistory history) {
    return new FlowDefinitionHistoryResponse(
        history.getId(),
        history.getVersion(),
        history.getStatus(),
        history.getDefinition(),
        StringUtils.hasText(history.getChangeNotes()) ? history.getChangeNotes() : null,
        history.getCreatedBy(),
        history.getCreatedAt());
  }
}
