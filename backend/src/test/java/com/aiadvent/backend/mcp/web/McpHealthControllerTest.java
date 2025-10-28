package com.aiadvent.backend.mcp.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.aiadvent.backend.mcp.service.McpCatalogService;
import com.aiadvent.backend.mcp.web.dto.McpCatalogResponse;
import com.aiadvent.backend.mcp.web.dto.McpCatalogResponse.McpServer;
import com.aiadvent.backend.mcp.web.dto.McpCatalogResponse.McpServerStatus;
import com.aiadvent.backend.mcp.web.dto.McpCatalogResponse.McpTool;
import com.aiadvent.backend.mcp.web.dto.McpHealthEvent;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.http.codec.ServerSentEvent;

@ExtendWith(MockitoExtension.class)
class McpHealthControllerTest {

  @Mock private McpCatalogService catalogService;

  private McpHealthController controller;

  @BeforeEach
  void setUp() {
    controller = new McpHealthController(catalogService);
  }

  @Test
  void streamHealthProducesEventsForServers() {
    McpServer perplexity =
        new McpServer(
            "perplexity",
            "Perplexity",
            null,
            List.of(),
            McpServerStatus.UP,
            List.of(new McpTool("perplexity_search", "Search", null, "perplexity_search", 1, true)));
    McpServer agentops =
        new McpServer(
            "agentops",
            "Agent Ops",
            null,
            List.of(),
            McpServerStatus.DOWN,
            List.of(new McpTool("agent_ops.list_agents", "List", null, "agent_ops.list_agents", 1, false)));
    when(catalogService.getCatalog()).thenReturn(new McpCatalogResponse(List.of(perplexity, agentops)));

    List<ServerSentEvent<McpHealthEvent>> events =
        controller.streamHealth(Duration.ofMillis(100)).take(2).collectList().block(Duration.ofSeconds(1));

    assertThat(events).hasSize(2);
    assertThat(events.get(0).data().serverId()).isEqualTo("perplexity");
    assertThat(events.get(1).data().serverId()).isEqualTo("agentops");
  }
}

