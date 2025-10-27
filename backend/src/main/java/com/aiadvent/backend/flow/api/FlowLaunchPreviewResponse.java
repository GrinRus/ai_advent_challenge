package com.aiadvent.backend.flow.api;

import com.aiadvent.backend.chat.config.ChatProviderType;
import com.aiadvent.backend.chat.provider.model.ChatRequestOverrides;
import com.aiadvent.backend.flow.agent.options.AgentInvocationOptions;
import com.aiadvent.backend.flow.config.FlowStepTransitions;
import com.aiadvent.backend.flow.config.MemoryReadConfig;
import com.aiadvent.backend.flow.config.MemoryWriteConfig;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record FlowLaunchPreviewResponse(
    UUID definitionId,
    String definitionName,
    int definitionVersion,
    String description,
    String startStepId,
    List<Step> steps,
    CostEstimate totalEstimate) {

  public record Step(
      String id,
      String name,
      String prompt,
      Agent agent,
      ChatRequestOverrides overrides,
      List<MemoryReadConfig> memoryReads,
      List<MemoryWriteConfig> memoryWrites,
      FlowStepTransitions transitions,
      int maxAttempts,
      CostEstimate estimate) {}

  public record Agent(
      UUID agentVersionId,
      int agentVersionNumber,
      UUID agentDefinitionId,
      String agentIdentifier,
      String agentDisplayName,
      String systemPrompt,
      ChatProviderType providerType,
      String providerId,
      String providerDisplayName,
      String modelId,
      String modelDisplayName,
      Integer modelContextWindow,
      Integer modelMaxOutputTokens,
      boolean syncOnly,
      Integer maxTokens,
      AgentInvocationOptions invocationOptions,
      Pricing pricing) {}

  public record Pricing(
      BigDecimal inputPer1KTokens, BigDecimal outputPer1KTokens, String currency) {}

  public record CostEstimate(
      Long promptTokens,
      Long completionTokens,
      Long totalTokens,
      BigDecimal inputCost,
      BigDecimal outputCost,
      BigDecimal totalCost,
      String currency) {}
}
