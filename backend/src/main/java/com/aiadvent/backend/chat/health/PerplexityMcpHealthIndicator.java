package com.aiadvent.backend.chat.health;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

@Component
public class PerplexityMcpHealthIndicator implements HealthIndicator {

  private final ObjectProvider<SyncMcpToolCallbackProvider> toolCallbackProvider;
  private final Timer latencyTimer;
  private final Counter errorCounter;

  public PerplexityMcpHealthIndicator(
      ObjectProvider<SyncMcpToolCallbackProvider> toolCallbackProvider, MeterRegistry meterRegistry) {
    this.toolCallbackProvider = toolCallbackProvider;
    this.latencyTimer =
        Timer.builder("perplexity_mcp_latency")
            .description("Latency of Perplexity MCP health checks")
            .register(meterRegistry);
    this.errorCounter =
        Counter.builder("perplexity_mcp_errors_total")
            .description("Number of Perplexity MCP health check errors")
            .register(meterRegistry);
  }

  @Override
  public Health health() {
    SyncMcpToolCallbackProvider provider = toolCallbackProvider.getIfAvailable();
    if (provider == null) {
      return Health.unknown().withDetail("status", "disabled").build();
    }

    long started = System.nanoTime();
    try {
      ToolCallback[] callbacks = provider.getToolCallbacks();
      long elapsed = System.nanoTime() - started;
      latencyTimer.record(elapsed, TimeUnit.NANOSECONDS);

      int toolCount = callbacks != null ? callbacks.length : 0;
      List<String> toolNames =
          callbacks == null
              ? List.of()
              : Arrays.stream(callbacks)
                  .map(ToolCallback::getToolDefinition)
                  .map(ToolDefinition::name)
                  .collect(Collectors.toList());

      Health.Builder builder =
          toolCount > 0 ? Health.up() : Health.unknown().withDetail("status", "no-tools");
      builder
          .withDetail("toolCount", toolCount)
          .withDetail("latencyMs", TimeUnit.NANOSECONDS.toMillis(elapsed));

      if (!CollectionUtils.isEmpty(toolNames)) {
        builder.withDetail("tools", toolNames);
      }

      return builder.build();
    } catch (Exception ex) {
      errorCounter.increment();
      return Health.down(ex).build();
    }
  }
}

