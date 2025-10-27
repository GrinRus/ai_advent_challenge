package com.aiadvent.backend.flow.api;

import com.aiadvent.backend.flow.agent.options.AgentInvocationOptions;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.List;

public record AgentConstructorPreviewResponse(
    AgentInvocationOptions proposed,
    List<DiffEntry> diff,
    PreviewCostEstimate costEstimate,
    List<ToolCoverage> toolCoverage,
    List<ValidationIssue> warnings) {

  public record DiffEntry(String path, JsonNode oldValue, JsonNode newValue) {}

  public record PreviewCostEstimate(
      Long promptTokens,
      Long completionTokens,
      BigDecimal estimatedCost,
      String currency,
      Long estimatedLatencyMs) {}

  public record ToolCoverage(String toolCode, boolean available, String message) {}
}

