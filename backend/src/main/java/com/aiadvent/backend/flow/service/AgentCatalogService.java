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
import com.fasterxml.jackson.databind.JsonNode;
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

  private static final String FLOW_OPS_TEMPLATE_IDENTIFIER = "flow-ops-operator";
  private static final String FLOW_OPS_TEMPLATE_DISPLAY_NAME = "Flow Ops Operator";
  private static final String FLOW_OPS_TEMPLATE_DESCRIPTION =
      "Default template for managing flow definitions via Flow Ops MCP tools.";
  private static final String FLOW_OPS_SYSTEM_PROMPT =
      "You are a flow operations assistant. Use the Flow Ops MCP tools to inspect definitions, "
          + "validate blueprints, and manage releases. Ask for confirmation before publish or rollback.";
  private static final String FLOW_OPS_PROVIDER_ID = "openai";
  private static final String FLOW_OPS_MODEL_ID = "gpt-4o-mini";
  private static final String FLOW_OPS_LIST_FLOWS_TOOL = "flow_ops.list_flows";
  private static final String FLOW_OPS_DIFF_VERSION_TOOL = "flow_ops.diff_flow_version";
  private static final String FLOW_OPS_VALIDATE_TOOL = "flow_ops.validate_blueprint";
  private static final String FLOW_OPS_PUBLISH_TOOL = "flow_ops.publish_flow";
  private static final String FLOW_OPS_ROLLBACK_TOOL = "flow_ops.rollback_flow";

  private static final String AGENT_OPS_TEMPLATE_IDENTIFIER = "agent-ops-admin";
  private static final String AGENT_OPS_TEMPLATE_DISPLAY_NAME = "Agent Ops Administrator";
  private static final String AGENT_OPS_TEMPLATE_DESCRIPTION =
      "Template agent for administering the agent catalog through Agent Ops MCP.";
  private static final String AGENT_OPS_SYSTEM_PROMPT =
      "You are an agent catalog administrator. Use the Agent Ops MCP tools to list, register, and "
          + "inspect agents. Always confirm destructive actions with the user.";
  private static final String AGENT_OPS_PROVIDER_ID = "openai";
  private static final String AGENT_OPS_MODEL_ID = "gpt-4o-mini";
  private static final String AGENT_OPS_LIST_AGENTS_TOOL = "agent_ops.list_agents";
  private static final String AGENT_OPS_REGISTER_TOOL = "agent_ops.register_agent";
  private static final String AGENT_OPS_PREVIEW_DEPENDENCIES_TOOL = "agent_ops.preview_dependencies";

  private static final String INSIGHT_TEMPLATE_IDENTIFIER = "insight-analyst";
  private static final String INSIGHT_TEMPLATE_DISPLAY_NAME = "Insight Analyst";
  private static final String INSIGHT_TEMPLATE_DESCRIPTION =
      "Template agent for analysing chat and flow telemetry with Insight MCP.";
  private static final String INSIGHT_SYSTEM_PROMPT =
      "You are an analytics specialist. Use the Insight MCP tools to review sessions, surface "
          + "summaries, search memory, and report telemetry trends.";
  private static final String INSIGHT_PROVIDER_ID = "openai";
  private static final String INSIGHT_MODEL_ID = "gpt-4o-mini";
  private static final String INSIGHT_RECENT_SESSIONS_TOOL = "insight.recent_sessions";
  private static final String INSIGHT_FETCH_SUMMARY_TOOL = "insight.fetch_summary";
  private static final String INSIGHT_SEARCH_MEMORY_TOOL = "insight.search_memory";
  private static final String INSIGHT_FETCH_METRICS_TOOL = "insight.fetch_metrics";

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
    return new AgentTemplateResponse(
        List.of(
            buildPerplexityResearchTemplate(),
            buildFlowOpsOperatorTemplate(),
            buildAgentOpsAdministratorTemplate(),
            buildInsightAnalystTemplate()));
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
    ChatProviderType providerType =
        resolveConfiguredProviderType(PERPLEXITY_PROVIDER_ID, PERPLEXITY_MODEL_ID);

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
                    manualTool(
                        PERPLEXITY_DEEP_RESEARCH_TOOL,
                        1,
                        buildPerplexityDeepResearchOverrides()))),
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
        List.of(
            new AgentTemplateCapability(
                "perplexity.tools", buildPerplexityToolCapabilityPayload())));
  }

  private AgentTemplate buildFlowOpsOperatorTemplate() {
    ChatProviderType providerType =
        resolveConfiguredProviderType(FLOW_OPS_PROVIDER_ID, FLOW_OPS_MODEL_ID);

    AgentInvocationOptions invocationOptions =
        new AgentInvocationOptions(
            new AgentInvocationOptions.Provider(
                providerType,
                FLOW_OPS_PROVIDER_ID,
                FLOW_OPS_MODEL_ID,
                AgentInvocationOptions.InvocationMode.SYNC),
            new AgentInvocationOptions.Prompt(
                null,
                FLOW_OPS_SYSTEM_PROMPT,
                List.of(),
                new AgentInvocationOptions.GenerationDefaults(0.15, 0.85, 900)),
            new AgentInvocationOptions.MemoryPolicy(
                List.of("shared", "conversation"),
                14,
                150,
                AgentInvocationOptions.SummarizationStrategy.TAIL_WITH_SUMMARY,
                AgentInvocationOptions.OverflowAction.TRIM_OLDEST),
            new AgentInvocationOptions.RetryPolicy(
                3,
                250L,
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
                    manualTool(FLOW_OPS_LIST_FLOWS_TOOL, 1, buildLimitOverride(25)),
                    manualTool(FLOW_OPS_DIFF_VERSION_TOOL, 1, null),
                    manualTool(FLOW_OPS_VALIDATE_TOOL, 1, null),
                    manualTool(FLOW_OPS_PUBLISH_TOOL, 1, null),
                    manualTool(FLOW_OPS_ROLLBACK_TOOL, 1, null))),
            new AgentInvocationOptions.CostProfile(
                new BigDecimal("0.008"),
                new BigDecimal("0.028"),
                null,
                null,
                "USD"));

    return new AgentTemplate(
        FLOW_OPS_TEMPLATE_IDENTIFIER,
        FLOW_OPS_TEMPLATE_DISPLAY_NAME,
        FLOW_OPS_TEMPLATE_DESCRIPTION,
        invocationOptions,
        true,
        1_100,
        List.of(
            new AgentTemplateCapability(
                "flow.ops.tools",
                buildCapabilityPayload(
                    FLOW_OPS_LIST_FLOWS_TOOL,
                    FLOW_OPS_LIST_FLOWS_TOOL,
                    FLOW_OPS_DIFF_VERSION_TOOL,
                    FLOW_OPS_VALIDATE_TOOL,
                    FLOW_OPS_PUBLISH_TOOL,
                    FLOW_OPS_ROLLBACK_TOOL))));
  }

  private AgentTemplate buildAgentOpsAdministratorTemplate() {
    ChatProviderType providerType =
        resolveConfiguredProviderType(AGENT_OPS_PROVIDER_ID, AGENT_OPS_MODEL_ID);

    AgentInvocationOptions invocationOptions =
        new AgentInvocationOptions(
            new AgentInvocationOptions.Provider(
                providerType,
                AGENT_OPS_PROVIDER_ID,
                AGENT_OPS_MODEL_ID,
                AgentInvocationOptions.InvocationMode.SYNC),
            new AgentInvocationOptions.Prompt(
                null,
                AGENT_OPS_SYSTEM_PROMPT,
                List.of(),
                new AgentInvocationOptions.GenerationDefaults(0.2, 0.85, 850)),
            new AgentInvocationOptions.MemoryPolicy(
                List.of("shared", "conversation"),
                14,
                120,
                AgentInvocationOptions.SummarizationStrategy.TAIL_WITH_SUMMARY,
                AgentInvocationOptions.OverflowAction.TRIM_OLDEST),
            new AgentInvocationOptions.RetryPolicy(
                3,
                250L,
                2.0,
                List.of(429, 500, 502, 503, 504),
                40_000L,
                100_000L,
                120L),
            new AgentInvocationOptions.AdvisorSettings(
                new AgentInvocationOptions.AdvisorSettings.AdvisorToggle(true),
                new AgentInvocationOptions.AdvisorSettings.AuditSettings(true, true),
                AgentInvocationOptions.AdvisorSettings.RoutingSettings.disabled()),
            new AgentInvocationOptions.Tooling(
                List.of(
                    manualTool(AGENT_OPS_LIST_AGENTS_TOOL, 1, buildLimitOverride(40)),
                    manualTool(
                        AGENT_OPS_REGISTER_TOOL, 1, buildBooleanOverride("active", true)),
                    manualTool(AGENT_OPS_PREVIEW_DEPENDENCIES_TOOL, 1, null))),
            new AgentInvocationOptions.CostProfile(
                new BigDecimal("0.008"),
                new BigDecimal("0.028"),
                null,
                null,
                "USD"));

    return new AgentTemplate(
        AGENT_OPS_TEMPLATE_IDENTIFIER,
        AGENT_OPS_TEMPLATE_DISPLAY_NAME,
        AGENT_OPS_TEMPLATE_DESCRIPTION,
        invocationOptions,
        true,
        900,
        List.of(
            new AgentTemplateCapability(
                "agent.ops.tools",
                buildCapabilityPayload(
                    AGENT_OPS_LIST_AGENTS_TOOL,
                    AGENT_OPS_LIST_AGENTS_TOOL,
                    AGENT_OPS_REGISTER_TOOL,
                    AGENT_OPS_PREVIEW_DEPENDENCIES_TOOL))));
  }

  private AgentTemplate buildInsightAnalystTemplate() {
    ChatProviderType providerType =
        resolveConfiguredProviderType(INSIGHT_PROVIDER_ID, INSIGHT_MODEL_ID);

    AgentInvocationOptions invocationOptions =
        new AgentInvocationOptions(
            new AgentInvocationOptions.Provider(
                providerType,
                INSIGHT_PROVIDER_ID,
                INSIGHT_MODEL_ID,
                AgentInvocationOptions.InvocationMode.SYNC),
            new AgentInvocationOptions.Prompt(
                null,
                INSIGHT_SYSTEM_PROMPT,
                List.of(),
                new AgentInvocationOptions.GenerationDefaults(0.15, 0.85, 900)),
            new AgentInvocationOptions.MemoryPolicy(
                List.of("shared", "conversation"),
                14,
                120,
                AgentInvocationOptions.SummarizationStrategy.TAIL_WITH_SUMMARY,
                AgentInvocationOptions.OverflowAction.TRIM_OLDEST),
            new AgentInvocationOptions.RetryPolicy(
                3,
                250L,
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
                    manualTool(INSIGHT_RECENT_SESSIONS_TOOL, 1, buildLimitOverride(20)),
                    manualTool(INSIGHT_FETCH_SUMMARY_TOOL, 1, null),
                    manualTool(INSIGHT_SEARCH_MEMORY_TOOL, 1, buildLimitOverride(25)),
                    manualTool(INSIGHT_FETCH_METRICS_TOOL, 1, null))),
            new AgentInvocationOptions.CostProfile(
                new BigDecimal("0.008"),
                new BigDecimal("0.028"),
                null,
                null,
                "USD"));

    return new AgentTemplate(
        INSIGHT_TEMPLATE_IDENTIFIER,
        INSIGHT_TEMPLATE_DISPLAY_NAME,
        INSIGHT_TEMPLATE_DESCRIPTION,
        invocationOptions,
        true,
        950,
        List.of(
            new AgentTemplateCapability(
                "insight.tools",
                buildCapabilityPayload(
                    INSIGHT_RECENT_SESSIONS_TOOL,
                    INSIGHT_RECENT_SESSIONS_TOOL,
                    INSIGHT_FETCH_SUMMARY_TOOL,
                    INSIGHT_SEARCH_MEMORY_TOOL,
                    INSIGHT_FETCH_METRICS_TOOL))));
  }

  private ChatProviderType resolveConfiguredProviderType(String providerId, String modelId) {
    ChatProvidersProperties.Provider providerConfig =
        chatProviderRegistry.requireProvider(providerId);
    if (StringUtils.hasText(modelId)) {
      chatProviderRegistry.requireModel(providerId, modelId);
    }
    if (providerConfig != null && providerConfig.getType() != null) {
      return providerConfig.getType();
    }
    return ChatProviderType.OPENAI;
  }

  private AgentInvocationOptions.ToolBinding manualTool(
      String toolCode, int schemaVersion, JsonNode overrides) {
    return new AgentInvocationOptions.ToolBinding(
        toolCode,
        schemaVersion,
        AgentInvocationOptions.ExecutionMode.MANUAL,
        overrides,
        null);
  }

  private ObjectNode buildLimitOverride(int limit) {
    ObjectNode node = objectMapper.createObjectNode();
    node.put("limit", limit);
    return node;
  }

  private ObjectNode buildBooleanOverride(String field, boolean value) {
    ObjectNode node = objectMapper.createObjectNode();
    node.put(field, value);
    return node;
  }

  private ObjectNode buildCapabilityPayload(String defaultTool, String... options) {
    ObjectNode payload = objectMapper.createObjectNode();
    payload.put("default", defaultTool);
    ArrayNode array = payload.putArray("options");
    java.util.LinkedHashSet<String> unique = new java.util.LinkedHashSet<>();
    if (StringUtils.hasText(defaultTool)) {
      unique.add(defaultTool);
    }
    if (options != null) {
      for (String option : options) {
        if (StringUtils.hasText(option)) {
          unique.add(option);
        }
      }
    }
    unique.forEach(array::add);
    return payload;
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
    return buildCapabilityPayload(
        PERPLEXITY_SEARCH_TOOL, PERPLEXITY_SEARCH_TOOL, PERPLEXITY_DEEP_RESEARCH_TOOL);
  }
}
