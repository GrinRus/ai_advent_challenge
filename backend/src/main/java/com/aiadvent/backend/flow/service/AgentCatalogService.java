package com.aiadvent.backend.flow.service;

import com.aiadvent.backend.chat.config.ChatProviderType;
import com.aiadvent.backend.chat.config.ChatProvidersProperties;
import com.aiadvent.backend.chat.provider.ChatProviderRegistry;
import com.aiadvent.backend.flow.agent.model.AgentCapabilityPayload;
import com.aiadvent.backend.flow.agent.options.AgentInvocationOptions;
import com.aiadvent.backend.flow.api.AgentCapabilityRequest;
import com.aiadvent.backend.flow.api.AgentDefinitionRequest;
import com.aiadvent.backend.flow.api.AgentDefinitionStatusRequest;
import com.aiadvent.backend.flow.api.AgentTemplateResponse;
import com.aiadvent.backend.flow.api.AgentTemplateResponse.AgentTemplate;
import com.aiadvent.backend.flow.api.AgentTemplateResponse.AgentTemplateCapability;
import com.aiadvent.backend.flow.api.AgentVersionPublishRequest;
import com.aiadvent.backend.flow.api.AgentVersionRequest;
import com.aiadvent.backend.flow.api.AgentVersionUpdateRequest;
import com.aiadvent.backend.flow.domain.AgentCapability;
import com.aiadvent.backend.flow.domain.AgentDefinition;
import com.aiadvent.backend.flow.domain.AgentVersion;
import com.aiadvent.backend.flow.domain.AgentVersionStatus;
import com.aiadvent.backend.flow.persistence.AgentCapabilityRepository;
import com.aiadvent.backend.flow.persistence.AgentDefinitionRepository;
import com.aiadvent.backend.flow.persistence.AgentVersionRepository;
import com.aiadvent.backend.flow.telemetry.ConstructorTelemetryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AgentCatalogService {

  private final AgentDefinitionRepository agentDefinitionRepository;
  private final AgentVersionRepository agentVersionRepository;
  private final AgentCapabilityRepository agentCapabilityRepository;
  private final ChatProviderRegistry chatProviderRegistry;
  private final ConstructorTelemetryService constructorTelemetryService;
  private final ObjectMapper objectMapper;

  private static final String PERPLEXITY_TEMPLATE_IDENTIFIER = "perplexity-research";
  private static final String PERPLEXITY_TEMPLATE_DISPLAY_NAME = "Perplexity Research";
  private static final String PERPLEXITY_TEMPLATE_DESCRIPTION =
      "Perplexity-powered research agent template";
  private static final String PERPLEXITY_PROVIDER_ID = "openai";
  private static final String PERPLEXITY_MODEL_ID = "gpt-4o-mini";
  private static final String PERPLEXITY_SEARCH_TOOL = "perplexity_search";
  private static final String PERPLEXITY_DEEP_RESEARCH_TOOL = "perplexity_deep_research";

  public AgentCatalogService(
      AgentDefinitionRepository agentDefinitionRepository,
      AgentVersionRepository agentVersionRepository,
      AgentCapabilityRepository agentCapabilityRepository,
      ChatProviderRegistry chatProviderRegistry,
      ConstructorTelemetryService constructorTelemetryService,
      ObjectMapper objectMapper) {
    this.agentDefinitionRepository = agentDefinitionRepository;
    this.agentVersionRepository = agentVersionRepository;
    this.agentCapabilityRepository = agentCapabilityRepository;
    this.chatProviderRegistry = chatProviderRegistry;
    this.constructorTelemetryService = constructorTelemetryService;
    this.objectMapper = objectMapper;
  }

  @Transactional(readOnly = true)
  public List<AgentDefinition> listDefinitions() {
    return agentDefinitionRepository.findAllByOrderByUpdatedAtDesc();
  }

  @Transactional(readOnly = true)
  public AgentTemplateResponse listTemplates() {
    return new AgentTemplateResponse(List.of(buildPerplexityResearchTemplate()));
  }

  @Transactional(readOnly = true)
  public AgentDefinition getDefinition(UUID definitionId) {
    return agentDefinitionRepository
        .findById(definitionId)
        .orElseThrow(
            () ->
                new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "Agent definition not found: " + definitionId));
  }

  @Transactional(readOnly = true)
  public AgentVersion getVersion(UUID versionId) {
    return agentVersionRepository
        .findById(versionId)
        .orElseThrow(
            () ->
                new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "Agent version not found: " + versionId));
  }

  @Transactional
  public AgentDefinition createDefinition(AgentDefinitionRequest request) {
    String actor = request != null ? request.createdBy() : null;
    try {
      if (!StringUtils.hasText(request.identifier())) {
        throw new IllegalArgumentException("Agent identifier must not be blank");
      }
      if (!StringUtils.hasText(request.displayName())) {
        throw new IllegalArgumentException("Agent displayName must not be blank");
      }

      String identifier = request.identifier().trim();
      if (agentDefinitionRepository.existsByIdentifierIgnoreCase(identifier)) {
        throw new ResponseStatusException(
            HttpStatus.CONFLICT, "Agent identifier already exists: " + identifier);
      }

      String displayName = request.displayName().trim();
      String description =
          request.description() != null && !request.description().isBlank()
              ? request.description().trim()
              : null;
      boolean active = request.active() == null || request.active();

      if (!StringUtils.hasText(request.createdBy())) {
        throw new IllegalArgumentException("Field 'createdBy' must not be blank");
      }

      AgentDefinition definition =
          new AgentDefinition(identifier, displayName, description, active);
      definition.setCreatedBy(request.createdBy().trim());
      definition.setUpdatedBy(request.createdBy().trim());

      AgentDefinition saved = agentDefinitionRepository.save(definition);
      constructorTelemetryService.recordAgentDefinitionSave("create", saved, actor);
      return saved;
    } catch (IllegalArgumentException | IllegalStateException | ResponseStatusException ex) {
      constructorTelemetryService.recordValidationError(
          "agent_definition", "create", actor, ex);
      throw ex;
    }
  }

  @Transactional
  public AgentDefinition updateDefinition(UUID definitionId, AgentDefinitionRequest request) {
    String actor = request != null ? request.updatedBy() : null;
    try {
      AgentDefinition definition = getDefinition(definitionId);

      if (StringUtils.hasText(request.identifier())) {
        String newIdentifier = request.identifier().trim();
        if (!newIdentifier.equalsIgnoreCase(definition.getIdentifier())
            && agentDefinitionRepository.existsByIdentifierIgnoreCase(newIdentifier)) {
          throw new ResponseStatusException(
              HttpStatus.CONFLICT, "Agent identifier already exists: " + newIdentifier);
        }
        definition.setIdentifier(newIdentifier);
      }

      if (!StringUtils.hasText(request.displayName())) {
        throw new IllegalArgumentException("Agent displayName must not be blank");
      }
      definition.setDisplayName(request.displayName().trim());

      definition.setDescription(
          request.description() != null && !request.description().isBlank()
              ? request.description().trim()
              : null);

      if (request.active() != null) {
        definition.setActive(request.active());
      }

      if (!StringUtils.hasText(request.updatedBy())) {
        throw new IllegalArgumentException("Field 'updatedBy' must not be blank");
      }
      definition.setUpdatedBy(request.updatedBy().trim());

      AgentDefinition saved = agentDefinitionRepository.save(definition);
      constructorTelemetryService.recordAgentDefinitionSave("update", saved, actor);
      return saved;
    } catch (IllegalArgumentException | IllegalStateException | ResponseStatusException ex) {
      constructorTelemetryService.recordValidationError(
          "agent_definition", "update", actor, ex);
      throw ex;
    }
  }

  @Transactional
  public AgentDefinition updateDefinitionStatus(
      UUID definitionId, AgentDefinitionStatusRequest request) {
    String actor = request != null ? request.updatedBy() : null;
    try {
      AgentDefinition definition = getDefinition(definitionId);
      if (request == null || request.active() == null) {
        throw new IllegalArgumentException("Field 'active' is required");
      }
      if (!StringUtils.hasText(request.updatedBy())) {
        throw new IllegalArgumentException("Field 'updatedBy' must not be blank");
      }
      definition.setActive(request.active());
      definition.setUpdatedBy(request.updatedBy().trim());
      AgentDefinition saved = agentDefinitionRepository.save(definition);
      constructorTelemetryService.recordAgentDefinitionSave("status", saved, actor);
      return saved;
    } catch (IllegalArgumentException | IllegalStateException | ResponseStatusException ex) {
      constructorTelemetryService.recordValidationError(
          "agent_definition", "status", actor, ex);
      throw ex;
    }
  }

  @Transactional(readOnly = true)
  public List<AgentVersion> listVersions(UUID definitionId) {
    AgentDefinition definition = getDefinition(definitionId);
    return agentVersionRepository.findByAgentDefinitionOrderByVersionDesc(definition);
  }

  @Transactional
  public AgentVersion createVersion(UUID definitionId, AgentVersionRequest request) {
    String actor = request != null ? request.createdBy() : null;
    try {
      AgentDefinition definition = getDefinition(definitionId);

      if (request == null) {
        throw new IllegalArgumentException("Agent version request must not be null");
      }
      if (!StringUtils.hasText(request.providerId())) {
        throw new IllegalArgumentException("Field 'providerId' must not be blank");
      }
      if (!StringUtils.hasText(request.modelId())) {
        throw new IllegalArgumentException("Field 'modelId' must not be blank");
      }
      if (!StringUtils.hasText(request.systemPrompt())) {
        throw new IllegalArgumentException("Field 'systemPrompt' must not be blank");
      }
      if (request.invocationOptions() == null) {
        throw new IllegalArgumentException("Field 'invocationOptions' must not be null");
      }

      if (!StringUtils.hasText(request.createdBy())) {
        throw new IllegalArgumentException("Field 'createdBy' must not be blank");
      }

      String providerId = request.providerId().trim();
      ChatProvidersProperties.Provider providerConfig =
          chatProviderRegistry.requireProvider(providerId);

      ChatProviderType providerType =
          resolveProviderType(request.providerType(), providerConfig.getType(), providerId);

      String modelId = request.modelId().trim();
      chatProviderRegistry.requireModel(providerId, modelId);

      int nextVersion =
          agentVersionRepository
                  .findTopByAgentDefinitionOrderByVersionDesc(definition)
                  .map(AgentVersion::getVersion)
                  .orElse(0)
              + 1;

      AgentVersion version =
          new AgentVersion(
              definition, nextVersion, AgentVersionStatus.DRAFT, providerType, providerId, modelId);

      version.setSystemPrompt(request.systemPrompt().trim());
      if (request.invocationOptions() == null) {
        throw new IllegalArgumentException("Field 'invocationOptions' must not be null");
      }
      AgentInvocationOptions invocationOptions = request.invocationOptions();
      AgentInvocationOptions.Provider invocationProvider = invocationOptions.provider();
      if (invocationProvider == null || !StringUtils.hasText(invocationProvider.id())) {
        throw new IllegalArgumentException(
            "Field 'invocationOptions.provider.id' must not be blank");
      }
      if (!invocationProvider.id().equals(providerId)) {
        throw new IllegalArgumentException(
            "Field 'invocationOptions.provider.id' must match providerId");
      }
      if (!StringUtils.hasText(invocationProvider.modelId())) {
        throw new IllegalArgumentException(
            "Field 'invocationOptions.provider.modelId' must not be blank");
      }
      if (invocationProvider.type() != null && invocationProvider.type() != providerType) {
        throw new IllegalArgumentException(
            "Field 'invocationOptions.provider.type' must match providerType");
      }
      version.setInvocationOptions(invocationOptions);
      version.setSyncOnly(request.syncOnly() == null || request.syncOnly());
      version.setMaxTokens(request.maxTokens());
      version.setCreatedBy(request.createdBy().trim());
      version.setUpdatedBy(request.createdBy().trim());

      AgentVersion saved = agentVersionRepository.save(version);
      replaceCapabilities(saved, request.capabilities());
      constructorTelemetryService.recordAgentVersionSave("create", saved, actor);
      return saved;
    } catch (IllegalArgumentException | IllegalStateException | ResponseStatusException ex) {
      constructorTelemetryService.recordValidationError(
          "agent_version", "create", actor, ex);
      throw ex;
    }
  }

  @Transactional
  public AgentVersion updateVersion(UUID versionId, AgentVersionUpdateRequest request) {
    String actor = request != null ? request.updatedBy() : null;
    try {
      if (request == null) {
        throw new IllegalArgumentException("Agent version update request must not be null");
      }
      AgentVersion version = getVersion(versionId);

      if (version.getStatus() != AgentVersionStatus.DRAFT) {
        throw new ResponseStatusException(
            HttpStatus.CONFLICT, "Only DRAFT versions can be updated: " + versionId);
      }

      if (!StringUtils.hasText(request.systemPrompt())) {
        throw new IllegalArgumentException("Field 'systemPrompt' must not be blank");
      }
      if (request.invocationOptions() == null) {
        throw new IllegalArgumentException("Field 'invocationOptions' must not be null");
      }
      if (!StringUtils.hasText(request.updatedBy())) {
        throw new IllegalArgumentException("Field 'updatedBy' must not be blank");
      }

      AgentInvocationOptions invocationOptions = request.invocationOptions();
      AgentInvocationOptions.Provider invocationProvider = invocationOptions.provider();
      if (invocationProvider == null || !StringUtils.hasText(invocationProvider.id())) {
        throw new IllegalArgumentException(
            "Field 'invocationOptions.provider.id' must not be blank");
      }
      if (!invocationProvider.id().equals(version.getProviderId())) {
        throw new IllegalArgumentException(
            "Field 'invocationOptions.provider.id' must match agent version providerId");
      }
      if (!StringUtils.hasText(invocationProvider.modelId())) {
        throw new IllegalArgumentException(
            "Field 'invocationOptions.provider.modelId' must not be blank");
      }
      if (invocationProvider.type() != null
          && invocationProvider.type() != version.getProviderType()) {
        throw new IllegalArgumentException(
            "Field 'invocationOptions.provider.type' must match agent version providerType");
      }

      version.setSystemPrompt(request.systemPrompt().trim());
      version.setInvocationOptions(invocationOptions);
      version.setSyncOnly(request.syncOnly() == null || request.syncOnly());
      version.setMaxTokens(request.maxTokens());
      version.setUpdatedBy(request.updatedBy().trim());

      AgentVersion saved = agentVersionRepository.save(version);
      replaceCapabilities(saved, request.capabilities());
      constructorTelemetryService.recordAgentVersionSave("update", saved, actor);
      return saved;
    } catch (IllegalArgumentException | IllegalStateException | ResponseStatusException ex) {
      constructorTelemetryService.recordValidationError(
          "agent_version", "update", actor, ex);
      throw ex;
    }
  }

  @Transactional
  public AgentVersion publishVersion(UUID versionId, AgentVersionPublishRequest request) {
    String actor = null;
    try {
      AgentVersion version = getVersion(versionId);
      actor =
          request != null && StringUtils.hasText(request.updatedBy())
              ? request.updatedBy()
              : version.getUpdatedBy();
      if (version.getStatus() == AgentVersionStatus.DEPRECATED) {
        throw new ResponseStatusException(
            HttpStatus.CONFLICT, "Cannot publish deprecated agent version: " + versionId);
      }
      if (request != null && !CollectionUtils.isEmpty(request.capabilities())) {
        replaceCapabilities(version, request.capabilities());
      }
      if (request != null && StringUtils.hasText(request.updatedBy())) {
        version.setUpdatedBy(request.updatedBy().trim());
      }

      if (!StringUtils.hasText(version.getUpdatedBy())) {
        throw new IllegalArgumentException(
            "Field 'updatedBy' must be provided to publish version");
      }

      version.setStatus(AgentVersionStatus.PUBLISHED);
      version.setPublishedAt(Instant.now());

      AgentDefinition definition = version.getAgentDefinition();
      if (definition != null) {
        if (!definition.isActive()) {
          definition.setActive(true);
        }
        definition.setUpdatedBy(version.getUpdatedBy());
        agentDefinitionRepository.save(definition);
      }

      AgentVersion saved = agentVersionRepository.save(version);
      constructorTelemetryService.recordAgentVersionSave("publish", saved, actor);
      return saved;
    } catch (IllegalArgumentException | IllegalStateException | ResponseStatusException ex) {
      constructorTelemetryService.recordValidationError(
          "agent_version", "publish", actor, ex);
      throw ex;
    }
  }

  @Transactional
  public AgentVersion deprecateVersion(UUID versionId) {
    AgentVersion version = getVersion(versionId);
    try {
      version.setStatus(AgentVersionStatus.DEPRECATED);
      AgentVersion saved = agentVersionRepository.save(version);
      constructorTelemetryService.recordAgentVersionSave(
          "deprecate", saved, saved.getUpdatedBy());
      return saved;
    } catch (IllegalArgumentException | IllegalStateException | ResponseStatusException ex) {
      constructorTelemetryService.recordValidationError(
          "agent_version", "deprecate", version.getUpdatedBy(), ex);
      throw ex;
    }
  }

  @Transactional(readOnly = true)
  public Optional<AgentVersion> findLatestVersion(AgentDefinition definition) {
    return agentVersionRepository.findTopByAgentDefinitionOrderByVersionDesc(definition);
  }

  @Transactional(readOnly = true)
  public Optional<AgentVersion> findLatestPublishedVersion(AgentDefinition definition) {
    return agentVersionRepository.findTopByAgentDefinitionAndStatusOrderByVersionDesc(
        definition, AgentVersionStatus.PUBLISHED);
  }

  @Transactional(readOnly = true)
  public List<AgentCapability> listCapabilities(AgentVersion version) {
    return agentCapabilityRepository.findByAgentVersionOrderByIdAsc(version);
  }

  private ChatProviderType resolveProviderType(
      ChatProviderType requested,
      ChatProviderType configured,
      String providerId) {
    if (requested == null) {
      if (configured == null) {
        throw new IllegalArgumentException(
            "Provider type must be specified for provider: " + providerId);
      }
      return configured;
    }
    if (configured != null && requested != configured) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "Requested providerType "
              + requested
              + " does not match configuration for provider "
              + providerId);
    }
    return requested;
  }

  private void replaceCapabilities(AgentVersion version, List<AgentCapabilityRequest> requests) {
    agentCapabilityRepository.deleteByAgentVersion(version);
    if (CollectionUtils.isEmpty(requests)) {
      return;
    }
    for (AgentCapabilityRequest capabilityRequest : requests) {
      if (capabilityRequest == null || !StringUtils.hasText(capabilityRequest.capability())) {
        throw new IllegalArgumentException("Capability name must not be blank");
      }
      agentCapabilityRepository.save(
          new AgentCapability(
              version,
              capabilityRequest.capability().trim(),
              AgentCapabilityPayload.from(capabilityRequest.payload())));
    }
  }

  private AgentTemplate buildPerplexityResearchTemplate() {
    ChatProvidersProperties.Provider providerConfig =
        chatProviderRegistry.requireProvider(PERPLEXITY_PROVIDER_ID);
    ChatProviderType providerType =
        providerConfig != null && providerConfig.getType() != null
            ? providerConfig.getType()
            : ChatProviderType.OPENAI;

    AgentInvocationOptions invocationOptions =
        new AgentInvocationOptions(
            new AgentInvocationOptions.Provider(
                providerType,
                PERPLEXITY_PROVIDER_ID,
                PERPLEXITY_MODEL_ID,
                AgentInvocationOptions.InvocationMode.SYNC),
            new AgentInvocationOptions.Prompt(
                null,
                "You are a meticulous research analyst. Leverage the Perplexity MCP tooling to collect current information, then provide a concise brief that references numbered sources.",
                List.of(),
                new AgentInvocationOptions.GenerationDefaults(0.1, 0.85, 1_200)),
            new AgentInvocationOptions.MemoryPolicy(
                List.of("shared", "conversation"),
                30,
                200,
                AgentInvocationOptions.SummarizationStrategy.TAIL_WITH_SUMMARY,
                AgentInvocationOptions.OverflowAction.TRIM_OLDEST),
            new AgentInvocationOptions.RetryPolicy(
                4,
                300L,
                2.0,
                List.of(429, 500, 502, 503, 504),
                45_000L,
                120_000L,
                150L),
            new AgentInvocationOptions.AdvisorSettings(
                new AgentInvocationOptions.AdvisorSettings.AdvisorToggle(true),
                new AgentInvocationOptions.AdvisorSettings.AuditSettings(true, true),
                AgentInvocationOptions.AdvisorSettings.RoutingSettings.disabled()),
            new AgentInvocationOptions.Tooling(
                List.of(
                    new AgentInvocationOptions.ToolBinding(
                        PERPLEXITY_SEARCH_TOOL,
                        1,
                        AgentInvocationOptions.ExecutionMode.AUTO,
                        buildPerplexitySearchOverrides(),
                        null),
                    new AgentInvocationOptions.ToolBinding(
                        PERPLEXITY_DEEP_RESEARCH_TOOL,
                        1,
                        AgentInvocationOptions.ExecutionMode.MANUAL,
                        buildPerplexityDeepResearchOverrides(),
                        null))),
            new AgentInvocationOptions.CostProfile(
                new BigDecimal("0.008"),
                new BigDecimal("0.028"),
                new BigDecimal("0.001"),
                null,
                "USD"));

    return new AgentTemplate(
        PERPLEXITY_TEMPLATE_IDENTIFIER,
        PERPLEXITY_TEMPLATE_DISPLAY_NAME,
        PERPLEXITY_TEMPLATE_DESCRIPTION,
        invocationOptions,
        true,
        1_400,
        List.of(new AgentTemplateCapability("perplexity.tools", buildPerplexityToolCapabilityPayload())));
  }

  private ObjectNode buildPerplexitySearchOverrides() {
    ObjectNode node = objectMapper.createObjectNode();
    node.put("max_results", 8);
    return node;
  }

  private ObjectNode buildPerplexityDeepResearchOverrides() {
    ObjectNode node = objectMapper.createObjectNode();
    node.put("max_iterations", 3);
    return node;
  }

  private ObjectNode buildPerplexityToolCapabilityPayload() {
    ObjectNode payload = objectMapper.createObjectNode();
    payload.put("default", PERPLEXITY_SEARCH_TOOL);
    ArrayNode options = payload.putArray("options");
    options.add(PERPLEXITY_SEARCH_TOOL);
    options.add(PERPLEXITY_DEEP_RESEARCH_TOOL);
    return payload;
  }
}
