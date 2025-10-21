package com.aiadvent.backend.chat.token;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.TimeUnit;

public class TokenUsageMetrics {

  private final MeterRegistry meterRegistry;

  public TokenUsageMetrics(MeterRegistry meterRegistry) {
    this.meterRegistry = meterRegistry;
  }

  public void recordNativeUsage(String providerId, String modelId, Integer totalTokens) {
    counter("chat.usage.native.count", providerId, modelId).increment();
    if (totalTokens != null) {
      summary("chat.usage.native.tokens", providerId, modelId).record(totalTokens);
    }
  }

  public void recordFallbackUsage(String providerId, String modelId, Integer totalTokens) {
    counter("chat.usage.fallback.count", providerId, modelId).increment();
    if (totalTokens != null) {
      summary("chat.usage.fallback.tokens", providerId, modelId).record(totalTokens);
    }
  }

  public void recordFallbackDelta(
      String providerId,
      String modelId,
      String segment,
      Integer fallbackTokens,
      Integer nativeTokens) {
    if (fallbackTokens == null || nativeTokens == null) {
      return;
    }
    summary("chat.usage.fallback.delta.tokens", providerId, modelId, segment)
        .record(Math.abs(fallbackTokens - nativeTokens));
  }

  public void recordRedisRequest(String result, long durationNanos) {
    meterRegistry
        .counter("chat.token.cache.requests", "result", result)
        .increment();
    meterRegistry
        .timer("chat.token.cache.latency", "result", result)
        .record(durationNanos, TimeUnit.NANOSECONDS);
  }

  private Counter counter(String name, String providerId, String modelId) {
    return meterRegistry.counter(name, "provider", providerId, "model", modelId);
  }

  private DistributionSummary summary(String name, String providerId, String modelId) {
    return meterRegistry.summary(name, "provider", providerId, "model", modelId);
  }

  private DistributionSummary summary(String name, String providerId, String modelId, String segment) {
    return meterRegistry.summary(name, "provider", providerId, "model", modelId, "segment", segment);
  }
}
