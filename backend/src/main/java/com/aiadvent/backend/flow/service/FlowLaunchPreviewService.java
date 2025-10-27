package com.aiadvent.backend.flow.service;

import com.aiadvent.backend.chat.config.ChatProvidersProperties;
import com.aiadvent.backend.flow.api.FlowLaunchPreviewResponse;
import com.aiadvent.backend.chat.provider.model.ChatRequestOverrides;
import com.aiadvent.backend.flow.blueprint.FlowBlueprintCompiler;
import com.aiadvent.backend.flow.config.FlowDefinitionDocument;
import com.aiadvent.backend.flow.config.FlowStepConfig;
import com.aiadvent.backend.flow.domain.AgentDefinition;
import com.aiadvent.backend.flow.domain.AgentVersion;
import com.aiadvent.backend.flow.domain.AgentVersionStatus;
import com.aiadvent.backend.flow.domain.FlowDefinition;
import com.aiadvent.backend.flow.persistence.AgentVersionRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class FlowLaunchPreviewService {

  private static final BigDecimal ONE_THOUSAND = BigDecimal.valueOf(1000);

  private final FlowDefinitionService flowDefinitionService;
  private final FlowBlueprintCompiler flowBlueprintCompiler;
  private final AgentVersionRepository agentVersionRepository;
  private final ChatProvidersProperties chatProvidersProperties;

  public FlowLaunchPreviewService(
      FlowDefinitionService flowDefinitionService,
      FlowBlueprintCompiler flowBlueprintCompiler,
      AgentVersionRepository agentVersionRepository,
      ChatProvidersProperties chatProvidersProperties) {
    this.flowDefinitionService = flowDefinitionService;
    this.flowBlueprintCompiler = flowBlueprintCompiler;
    this.agentVersionRepository = agentVersionRepository;
    this.chatProvidersProperties = chatProvidersProperties;
  }

  @Transactional(readOnly = true)
  public FlowLaunchPreviewResponse preview(UUID definitionId) {
    FlowDefinition definition = flowDefinitionService.getActivePublishedDefinition(definitionId);
    FlowDefinitionDocument document = flowBlueprintCompiler.compile(definition);

    List<FlowLaunchPreviewResponse.Step> steps = new ArrayList<>();
    CostAccumulator totalAccumulator = new CostAccumulator();

    for (FlowStepConfig step : document.steps()) {
      AgentVersion agentVersion = resolveAgentVersion(step.agentVersionId());
      FlowLaunchPreviewResponse.Agent agent = buildAgent(agentVersion);
      FlowLaunchPreviewResponse.CostEstimate estimate =
          estimateCost(step, agentVersion, agent);

      steps.add(
          new FlowLaunchPreviewResponse.Step(
              step.id(),
              step.name(),
              step.prompt(),
              agent,
              normalizeOverrides(step.overrides()),
              step.memoryReads(),
              step.memoryWrites(),
              step.transitions(),
              step.maxAttempts(),
              estimate));

      totalAccumulator.add(estimate);
    }

    return new FlowLaunchPreviewResponse(
        definition.getId(),
        definition.getName(),
        definition.getVersion(),
        definition.getDescription(),
        document.startStepId(),
        List.copyOf(steps),
        totalAccumulator.toEstimate());
  }

  private AgentVersion resolveAgentVersion(UUID agentVersionId) {
    AgentVersion agentVersion =
        agentVersionRepository
            .findById(agentVersionId)
            .orElseThrow(
                () ->
                    new ResponseStatusException(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "Agent version not found: " + agentVersionId));

    if (agentVersion.getStatus() != AgentVersionStatus.PUBLISHED) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "Agent version is not published: " + agentVersionId);
    }
    AgentDefinition agentDefinition = agentVersion.getAgentDefinition();
    if (agentDefinition == null || !agentDefinition.isActive()) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "Agent definition for version "
              + agentVersionId
              + " is not active");
    }

    return agentVersion;
  }

  private FlowLaunchPreviewResponse.Agent buildAgent(AgentVersion agentVersion) {
    AgentDefinition agentDefinition = agentVersion.getAgentDefinition();

    ChatProvidersProperties.Provider providerConfig =
        chatProvidersProperties.getProviders().get(agentVersion.getProviderId());

    ChatProvidersProperties.Model modelConfig =
        providerConfig != null ? providerConfig.getModels().get(agentVersion.getModelId()) : null;

    FlowLaunchPreviewResponse.Pricing pricing =
        modelConfig != null
            ? new FlowLaunchPreviewResponse.Pricing(
                modelConfig.getPricing().getInputPer1KTokens(),
                modelConfig.getPricing().getOutputPer1KTokens(),
                modelConfig.getPricing().getCurrency())
            : new FlowLaunchPreviewResponse.Pricing(null, null, null);

    return new FlowLaunchPreviewResponse.Agent(
        agentVersion.getId(),
        agentVersion.getVersion(),
        agentDefinition != null ? agentDefinition.getId() : null,
        agentDefinition != null ? agentDefinition.getIdentifier() : null,
        agentDefinition != null ? agentDefinition.getDisplayName() : null,
        agentVersion.getSystemPrompt(),
        agentVersion.getProviderType(),
        agentVersion.getProviderId(),
        providerConfig != null ? providerConfig.getDisplayName() : null,
        agentVersion.getModelId(),
        modelConfig != null ? modelConfig.getDisplayName() : null,
        modelConfig != null ? modelConfig.getContextWindow() : null,
        modelConfig != null ? modelConfig.getMaxOutputTokens() : null,
        agentVersion.isSyncOnly(),
        agentVersion.getMaxTokens(),
        agentVersion.getDefaultOptions().asJson(),
        agentVersion.getCostProfile().asJson(),
        pricing);
  }

  private FlowLaunchPreviewResponse.CostEstimate estimateCost(
      FlowStepConfig stepConfig,
      AgentVersion agentVersion,
      FlowLaunchPreviewResponse.Agent agent) {
    long promptTokens = estimatePromptTokens(stepConfig.prompt());
    long completionTokens = estimateCompletionTokens(stepConfig, agentVersion, agent);
    long totalTokens = promptTokens + completionTokens;

    FlowLaunchPreviewResponse.Pricing pricing = agent.pricing();
    BigDecimal inputCost = computeCost(promptTokens, pricing.inputPer1KTokens());
    BigDecimal outputCost = computeCost(completionTokens, pricing.outputPer1KTokens());
    BigDecimal totalCost = inputCost.add(outputCost);

    String currency =
        Optional.ofNullable(pricing.currency()).filter(c -> !c.isBlank()).orElse("USD");

    return new FlowLaunchPreviewResponse.CostEstimate(
        promptTokens,
        completionTokens,
        totalTokens,
        inputCost,
        outputCost,
        totalCost,
        currency);
  }

  private long estimatePromptTokens(String prompt) {
    if (prompt == null || prompt.isBlank()) {
      return 64L;
    }
    int codePoints = prompt.codePointCount(0, prompt.length());
    long estimated = Math.round(codePoints / 4.0d);
    return Math.max(32L, estimated);
  }

  private long estimateCompletionTokens(
      FlowStepConfig stepConfig,
      AgentVersion agentVersion,
      FlowLaunchPreviewResponse.Agent agent) {
    Integer override = stepConfig.overrides() != null ? stepConfig.overrides().maxTokens() : null;
    if (override != null && override > 0) {
      return override.longValue();
    }
    if (agentVersion.getMaxTokens() != null && agentVersion.getMaxTokens() > 0) {
      return agentVersion.getMaxTokens().longValue();
    }

    if (agent.modelMaxOutputTokens() != null && agent.modelMaxOutputTokens() > 0) {
      return agent.modelMaxOutputTokens().longValue();
    }

    return 512L;
  }

  private BigDecimal computeCost(long tokens, BigDecimal ratePer1KTokens) {
    if (tokens <= 0 || ratePer1KTokens == null || ratePer1KTokens.compareTo(BigDecimal.ZERO) <= 0) {
      return BigDecimal.ZERO;
    }
    return ratePer1KTokens
        .multiply(BigDecimal.valueOf(tokens))
        .divide(ONE_THOUSAND, 6, RoundingMode.HALF_UP);
  }

  private ChatRequestOverrides normalizeOverrides(ChatRequestOverrides overrides) {
    if (overrides == null) {
      return null;
    }
    if (overrides.temperature() == null && overrides.topP() == null && overrides.maxTokens() == null) {
      return null;
    }
    return overrides;
  }

  private static final class CostAccumulator {
    private long promptTokens = 0L;
    private long completionTokens = 0L;
    private BigDecimal inputCost = BigDecimal.ZERO;
    private BigDecimal outputCost = BigDecimal.ZERO;
    private String currency = null;

    void add(FlowLaunchPreviewResponse.CostEstimate estimate) {
      FlowLaunchPreviewResponse.CostEstimate safeEstimate =
          estimate != null ? estimate : new FlowLaunchPreviewResponse.CostEstimate(0L, 0L, 0L, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, "USD");

      promptTokens += Optional.ofNullable(safeEstimate.promptTokens()).orElse(0L);
      completionTokens += Optional.ofNullable(safeEstimate.completionTokens()).orElse(0L);
      inputCost = inputCost.add(Optional.ofNullable(safeEstimate.inputCost()).orElse(BigDecimal.ZERO));
      outputCost = outputCost.add(Optional.ofNullable(safeEstimate.outputCost()).orElse(BigDecimal.ZERO));

      if (currency == null && safeEstimate.currency() != null && !safeEstimate.currency().isBlank()) {
        currency = safeEstimate.currency();
      }
    }

    FlowLaunchPreviewResponse.CostEstimate toEstimate() {
      long totalTokens = promptTokens + completionTokens;
      BigDecimal totalCost = inputCost.add(outputCost);
      return new FlowLaunchPreviewResponse.CostEstimate(
          promptTokens, completionTokens, totalTokens, inputCost, outputCost, totalCost, currency != null ? currency : "USD");
    }
  }
}
