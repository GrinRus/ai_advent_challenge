package com.aiadvent.backend.mcp.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.aiadvent.backend.mcp.service.McpCatalogService;
import com.aiadvent.backend.mcp.web.dto.McpCatalogResponse;
import com.aiadvent.backend.mcp.web.dto.McpCatalogResponse.McpServer;
import com.aiadvent.backend.mcp.web.dto.McpCatalogResponse.McpServerStatus;
import com.aiadvent.backend.mcp.web.dto.McpCatalogResponse.McpTool;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(MockitoExtension.class)
class McpHealthIndicatorTest {

  @Mock private McpCatalogService catalogService;

  private SimpleMeterRegistry meterRegistry;

  private McpHealthIndicator healthIndicator;

  @BeforeEach
  void setUp() {
    meterRegistry = new SimpleMeterRegistry();
    healthIndicator = new McpHealthIndicator(catalogService, meterRegistry);
  }

  @Test
  void returnsDownWhenServerIsDown() {
    McpServer downServer =
        new McpServer(
            "perplexity",
            "Perplexity MCP",
            null,
            List.of(),
            McpServerStatus.DOWN,
            List.of(new McpTool("perplexity_search", "Search", null, "perplexity_search", 1, false)));
    McpServer upServer =
        new McpServer(
            "agentops",
            "Agent Ops",
            null,
            List.of(),
            McpServerStatus.UP,
            List.of(new McpTool("agent_ops.list_agents", "List", null, "agent_ops.list_agents", 1, true)));

    when(catalogService.getCatalog()).thenReturn(new McpCatalogResponse(List.of(downServer, upServer)));

    Health health = healthIndicator.health();

    assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    assertThat(health.getDetails()).containsKey("servers");
    assertThat(meterRegistry.get("mcp_health_errors_total").tag("server", "perplexity").counter().count())
        .isEqualTo(1.0);
  }

  @Test
  void returnsUpWhenAllServersUp() {
    McpServer upServer =
        new McpServer(
            "agentops",
            "Agent Ops",
            null,
            List.of(),
            McpServerStatus.UP,
            List.of(new McpTool("agent_ops.list_agents", "List", null, "agent_ops.list_agents", 1, true)));

    when(catalogService.getCatalog()).thenReturn(new McpCatalogResponse(List.of(upServer)));

    Health health = healthIndicator.health();

    assertThat(health.getStatus()).isEqualTo(Status.UP);
    assertThat(health.getDetails()).containsKey("servers");
    assertThat(meterRegistry.find("mcp_health_errors_total").tags("server", "agentops").counter())
        .isNull();
  }
}
