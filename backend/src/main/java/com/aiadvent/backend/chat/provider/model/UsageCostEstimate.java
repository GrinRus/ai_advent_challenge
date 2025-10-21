package com.aiadvent.backend.chat.provider.model;

import java.math.BigDecimal;

public record UsageCostEstimate(
    Integer promptTokens,
    Integer completionTokens,
    Integer totalTokens,
    BigDecimal inputCost,
    BigDecimal outputCost,
    BigDecimal totalCost,
    String currency) {

  public static UsageCostEstimate empty() {
    return new UsageCostEstimate(null, null, null, null, null, null, null);
  }

  public boolean hasUsage() {
    return promptTokens != null || completionTokens != null || totalTokens != null;
  }

  public boolean hasCost() {
    return inputCost != null || outputCost != null || totalCost != null;
  }
}

