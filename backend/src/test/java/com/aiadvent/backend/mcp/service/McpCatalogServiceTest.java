package com.aiadvent.backend.mcp.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aiadvent.backend.flow.tool.domain.ToolDefinition;
import com.aiadvent.backend.flow.tool.domain.ToolDefinition.ToolCallType;
import com.aiadvent.backend.flow.tool.domain.ToolSchemaVersion;
import com.aiadvent.backend.flow.tool.persistence.ToolDefinitionRepository;
import com.aiadvent.backend.mcp.config.McpCatalogProperties;
import com.aiadvent.backend.mcp.web.dto.McpCatalogResponse;
import com.aiadvent.backend.mcp.web.dto.McpCatalogResponse.McpServer;
import com.aiadvent.backend.mcp.web.dto.McpCatalogResponse.McpServerStatus;
import com.aiadvent.backend.mcp.web.dto.McpCatalogResponse.McpTool;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;

class McpCatalogServiceTest {

  private ToolDefinitionRepository toolDefinitionRepository;
  private McpCatalogProperties catalogProperties;

  @BeforeEach
  void setUp() {
    toolDefinitionRepository = mock(ToolDefinitionRepository.class);
    catalogProperties = new McpCatalogProperties();

    McpCatalogProperties.ServerProperties agentOps = new McpCatalogProperties.ServerProperties();
    agentOps.setDisplayName("Agent Ops");
    agentOps.setTags(List.of("agentops"));
    catalogProperties.getServers().put("agentops", agentOps);
  }

  @Test
  void getCatalogMarksAvailableToolsAndStatuses() {
    McpCatalogProperties.ServerProperties flowOps = new McpCatalogProperties.ServerProperties();
    flowOps.setDisplayName("Flow Ops");
    flowOps.setDescription("Flow operations tooling");
    flowOps.setTags(List.of("flow", "ops"));
    catalogProperties.getServers().put("flowops", flowOps);

    ToolDefinition flowOpsList = definition(
        "flow_ops.list_flows",
        "Flow Ops · List",
        schemaVersion("flow_ops.list_flows", "flowops", "flow_ops.list_flows"));

    ToolDefinition agentOpsRegister = definition(
        "agent_ops.register_agent",
        "Agent Ops · Register",
        schemaVersion("agent_ops.register_agent", "agentops", "agent_ops.register_agent"));

    when(toolDefinitionRepository.findAllBySchemaVersionIsNotNull())
        .thenReturn(List.of(flowOpsList, agentOpsRegister));

    ToolCallback flowOpsCallback = mock(ToolCallback.class);
    org.springframework.ai.tool.definition.ToolDefinition flowOpsToolDefinition =
        mock(org.springframework.ai.tool.definition.ToolDefinition.class);
    when(flowOpsCallback.getToolDefinition()).thenReturn(flowOpsToolDefinition);
    when(flowOpsToolDefinition.name()).thenReturn("flow_ops.list_flows");

    SyncMcpToolCallbackProvider callbackProvider = new SyncMcpToolCallbackProvider() {
      @Override
      public ToolCallback[] getToolCallbacks() {
        return new ToolCallback[] {flowOpsCallback};
      }
    };

    McpCatalogService service = new McpCatalogService(
        toolDefinitionRepository,
        new StaticObjectProvider<>(callbackProvider),
        catalogProperties);

    McpCatalogResponse response = service.getCatalog();

    assertThat(response.servers()).hasSize(2);

    assertThat(response.servers())
        .extracting(McpServer::id)
        .containsExactly("flowops", "agentops");

    McpServer flowOpsServer = response.servers().get(0);
    assertThat(flowOpsServer.status()).isEqualTo(McpServerStatus.UP);
    assertThat(flowOpsServer.tools())
        .extracting(McpTool::code, McpTool::available)
        .containsExactly(tuple("flow_ops.list_flows", true));

    McpServer agentOpsServer = response.servers().get(1);
    assertThat(agentOpsServer.status()).isEqualTo(McpServerStatus.DOWN);
    assertThat(agentOpsServer.tools())
        .extracting(McpTool::code, McpTool::available)
        .containsExactly(tuple("agent_ops.register_agent", false));
  }

  @Test
  void getCatalogHumanizesServerIdAndReturnsUnknownStatusWithoutProvider() {
    ToolDefinition insightTool = definition(
        "insight.fetch_summary",
        "Insight · Summary",
        schemaVersion("insight.fetch_summary", "insight-telemetry", "insight.fetch_summary"));

    when(toolDefinitionRepository.findAllBySchemaVersionIsNotNull())
        .thenReturn(List.of(insightTool));

    McpCatalogService service = new McpCatalogService(
        toolDefinitionRepository,
        new StaticObjectProvider<>(null),
        catalogProperties);

    McpCatalogResponse response = service.getCatalog();

    assertThat(response.servers())
        .extracting(McpServer::id)
        .containsExactly("insight-telemetry", "agentops");

    McpServer insight = response.servers().get(0);
    assertThat(insight.displayName()).isEqualTo("Insight Telemetry");
    assertThat(insight.status()).isEqualTo(McpServerStatus.UNKNOWN);
    assertThat(insight.tools())
        .extracting(McpTool::code)
        .containsExactly("insight.fetch_summary");
  }

  private static ToolDefinition definition(String code, String name, ToolSchemaVersion schema) {
    ToolDefinition definition =
        new ToolDefinition(
            code,
            name,
            null,
            null,
            ToolCallType.MANUAL,
            List.of(),
            List.of(),
            null,
            null,
            null);
    definition.setSchemaVersion(schema);
    return definition;
  }

  private static ToolSchemaVersion schemaVersion(String toolCode, String serverId, String toolName) {
    return new ToolSchemaVersion(toolCode, 1, null, null, null, null, serverId, toolName, "stdio", null);
  }

  private static class StaticObjectProvider<T> implements ObjectProvider<T> {

    private final T value;

    private StaticObjectProvider(T value) {
      this.value = value;
    }

    @Override
    public T getObject(Object... args) {
      return getObject();
    }

    @Override
    public T getObject() {
      if (value == null) {
        throw new IllegalStateException("No instance available");
      }
      return value;
    }

    @Override
    public T getIfAvailable() {
      return value;
    }

    @Override
    public T getIfUnique() {
      return value;
    }

    @Override
    public Stream<T> stream() {
      return value == null ? Stream.empty() : Stream.of(value);
    }

    @Override
    public Stream<T> orderedStream() {
      return stream();
    }
  }
}
