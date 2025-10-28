package com.aiadvent.backend.mcp.health;

import com.aiadvent.backend.mcp.service.McpCatalogService;
import com.aiadvent.backend.mcp.web.dto.McpCatalogResponse;
import com.aiadvent.backend.mcp.web.dto.McpCatalogResponse.McpServer;
import com.aiadvent.backend.mcp.web.dto.McpCatalogResponse.McpServerStatus;
import com.aiadvent.backend.mcp.web.dto.McpCatalogResponse.McpTool;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component
public class McpHealthIndicator implements HealthIndicator {

  private final McpCatalogService catalogService;
  private final MeterRegistry meterRegistry;
  private final Map<String, Timer> latencyTimers = new ConcurrentHashMap<>();
  private final Map<String, Counter> errorCounters = new ConcurrentHashMap<>();

  public McpHealthIndicator(McpCatalogService catalogService, MeterRegistry meterRegistry) {
    this.catalogService = catalogService;
    this.meterRegistry = meterRegistry;
  }

  @Override
  public Health health() {
    long started = System.nanoTime();
    try {
      McpCatalogResponse catalog = catalogService.getCatalog();
      long elapsed = System.nanoTime() - started;
      Duration duration = Duration.ofNanos(elapsed);

      Map<String, Object> serverDetails = new LinkedHashMap<>();
      boolean anyDown = false;

      for (McpServer server : catalog.servers()) {
        recordLatency(server.id(), duration);

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("status", server.status());
        details.put("toolCount", server.tools().size());
        details.put(
            "availableTools",
            toCodeList(server.tools().stream().filter(McpTool::available).toList()));
        details.put(
            "unavailableTools",
            toCodeList(server.tools().stream().filter(tool -> !tool.available()).toList()));
        serverDetails.put(server.id(), details);

        if (server.status() == McpServerStatus.DOWN) {
          anyDown = true;
          recordError(server.id());
        }
      }

      Health.Builder builder = anyDown ? Health.down() : Health.up();
      builder.withDetail("latencyMs", TimeUnit.NANOSECONDS.toMillis(elapsed));
      builder.withDetail("servers", serverDetails);
      return builder.build();
    } catch (Exception ex) {
      // If catalog lookup fails entirely, record a synthetic error with serverId=unknown.
      recordError("unknown");
      return Health.down(ex).build();
    }
  }

  private List<String> toCodeList(List<McpTool> tools) {
    return tools.stream().map(McpTool::code).collect(Collectors.toList());
  }

  private void recordLatency(String serverId, Duration duration) {
    latencyTimers
        .computeIfAbsent(
            serverId,
            id ->
                Timer.builder("mcp_health_latency")
                    .tag("server", id)
                    .description("Latency of MCP health checks per server")
                    .register(meterRegistry))
        .record(duration);
  }

  private void recordError(String serverId) {
    errorCounters
        .computeIfAbsent(
            serverId,
            id ->
                Counter.builder("mcp_health_errors_total")
                    .tag("server", id)
                    .description("Number of MCP health check errors per server")
                    .register(meterRegistry))
        .increment();
  }
}

