package com.aiadvent.backend.flow.controller;

import com.aiadvent.backend.flow.api.AgentCapabilityResponse;
import com.aiadvent.backend.flow.api.AgentDefinitionRequest;
import com.aiadvent.backend.flow.api.AgentDefinitionResponse;
import com.aiadvent.backend.flow.api.AgentDefinitionStatusRequest;
import com.aiadvent.backend.flow.api.AgentDefinitionSummaryResponse;
import com.aiadvent.backend.flow.api.AgentVersionPublishRequest;
import com.aiadvent.backend.flow.api.AgentVersionRequest;
import com.aiadvent.backend.flow.api.AgentVersionUpdateRequest;
import com.aiadvent.backend.flow.api.AgentVersionResponse;
import com.aiadvent.backend.flow.domain.AgentDefinition;
import com.aiadvent.backend.flow.domain.AgentVersion;
import com.aiadvent.backend.flow.service.AgentCatalogService;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/agents")
public class AgentDefinitionController {

  private final AgentCatalogService agentCatalogService;

  public AgentDefinitionController(AgentCatalogService agentCatalogService) {
    this.agentCatalogService = agentCatalogService;
  }

  @GetMapping("/definitions")
  public List<AgentDefinitionSummaryResponse> listDefinitions() {
    return agentCatalogService.listDefinitions().stream()
        .map(this::toSummaryResponse)
        .collect(Collectors.toList());
  }

  @GetMapping("/definitions/{id}")
  public AgentDefinitionResponse getDefinition(@PathVariable UUID id) {
    AgentDefinition definition = agentCatalogService.getDefinition(id);
    List<AgentVersion> versions = agentCatalogService.listVersions(id);
    return toResponse(definition, versions);
  }

  @PostMapping("/definitions")
  @ResponseStatus(HttpStatus.CREATED)
  public AgentDefinitionResponse createDefinition(@RequestBody AgentDefinitionRequest request) {
    AgentDefinition definition = agentCatalogService.createDefinition(request);
    return toResponse(definition, List.of());
  }

  @PutMapping("/definitions/{id}")
  public AgentDefinitionResponse updateDefinition(
      @PathVariable UUID id, @RequestBody AgentDefinitionRequest request) {
    AgentDefinition definition = agentCatalogService.updateDefinition(id, request);
    List<AgentVersion> versions = agentCatalogService.listVersions(id);
    return toResponse(definition, versions);
  }

  @PatchMapping("/definitions/{id}")
  public AgentDefinitionResponse updateDefinitionStatus(
      @PathVariable UUID id, @RequestBody AgentDefinitionStatusRequest request) {
    AgentDefinition definition = agentCatalogService.updateDefinitionStatus(id, request);
    List<AgentVersion> versions = agentCatalogService.listVersions(id);
    return toResponse(definition, versions);
  }

  @GetMapping("/definitions/{id}/versions")
  public List<AgentVersionResponse> listVersions(@PathVariable UUID id) {
    return agentCatalogService.listVersions(id).stream()
        .map(this::toVersionResponse)
        .collect(Collectors.toList());
  }

  @GetMapping("/versions/{id}")
  public AgentVersionResponse getVersion(@PathVariable UUID id) {
    AgentVersion version = agentCatalogService.getVersion(id);
    return toVersionResponse(version);
  }

  @PostMapping("/definitions/{id}/versions")
  @ResponseStatus(HttpStatus.CREATED)
  public AgentVersionResponse createVersion(
      @PathVariable UUID id, @RequestBody AgentVersionRequest request) {
    AgentVersion version = agentCatalogService.createVersion(id, request);
    return toVersionResponse(version);
  }

  @PutMapping("/versions/{id}")
  public AgentVersionResponse updateVersion(
      @PathVariable UUID id, @RequestBody AgentVersionUpdateRequest request) {
    AgentVersion version = agentCatalogService.updateVersion(id, request);
    return toVersionResponse(version);
  }

  @PostMapping("/versions/{id}/publish")
  public AgentVersionResponse publishVersion(
      @PathVariable UUID id, @RequestBody(required = false) AgentVersionPublishRequest request) {
    AgentVersion version = agentCatalogService.publishVersion(id, request);
    return toVersionResponse(version);
  }

  @PostMapping("/versions/{id}/deprecate")
  public AgentVersionResponse deprecateVersion(@PathVariable UUID id) {
    AgentVersion version = agentCatalogService.deprecateVersion(id);
    return toVersionResponse(version);
  }

  private AgentDefinitionSummaryResponse toSummaryResponse(AgentDefinition definition) {
    var latestVersionOpt = agentCatalogService.findLatestVersion(definition);
    var latestPublishedOpt = agentCatalogService.findLatestPublishedVersion(definition);
    return new AgentDefinitionSummaryResponse(
        definition.getId(),
        definition.getIdentifier(),
        definition.getDisplayName(),
        definition.getDescription(),
        definition.isActive(),
        definition.getCreatedBy(),
        definition.getUpdatedBy(),
        definition.getCreatedAt(),
        definition.getUpdatedAt(),
        latestVersionOpt.map(AgentVersion::getVersion).orElse(null),
        latestPublishedOpt.map(AgentVersion::getVersion).orElse(null),
        latestPublishedOpt.map(AgentVersion::getPublishedAt).orElse(null));
  }

  private AgentDefinitionResponse toResponse(
      AgentDefinition definition, List<AgentVersion> versions) {
    List<AgentVersionResponse> versionResponses =
        versions.stream().map(this::toVersionResponse).toList();

    return new AgentDefinitionResponse(
        definition.getId(),
        definition.getIdentifier(),
        definition.getDisplayName(),
        definition.getDescription(),
        definition.isActive(),
        definition.getCreatedBy(),
        definition.getUpdatedBy(),
        definition.getCreatedAt(),
        definition.getUpdatedAt(),
        versionResponses);
  }

  private AgentVersionResponse toVersionResponse(AgentVersion version) {
    List<AgentCapabilityResponse> capabilityResponses =
        agentCatalogService.listCapabilities(version).stream()
            .map(
                capability ->
                    new AgentCapabilityResponse(
                        capability.getCapability(), capability.getPayload().asJson()))
            .toList();

    return new AgentVersionResponse(
        version.getId(),
        version.getVersion(),
        version.getStatus(),
        version.getProviderType(),
        version.getProviderId(),
        version.getModelId(),
        version.getSystemPrompt(),
        version.getInvocationOptions(),
        version.isSyncOnly(),
        version.getMaxTokens(),
        version.getCreatedBy(),
        version.getUpdatedBy(),
        version.getCreatedAt(),
        version.getPublishedAt(),
        capabilityResponses);
  }
}
