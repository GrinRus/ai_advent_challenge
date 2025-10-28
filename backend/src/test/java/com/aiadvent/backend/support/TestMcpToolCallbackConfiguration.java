package com.aiadvent.backend.support;

import org.mockito.Mockito;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration
public class TestMcpToolCallbackConfiguration {

  private static final String DEFAULT_SCHEMA = "{\"type\":\"object\"}";

  @Bean
  @Primary
  public SyncMcpToolCallbackProvider stubSyncMcpToolCallbackProvider() {
    SyncMcpToolCallbackProvider provider = Mockito.mock(SyncMcpToolCallbackProvider.class);
    ToolCallback[] callbacks = new ToolCallback[] {
        staticCallback("perplexity_search"),
        staticCallback("perplexity_deep_research"),
        staticCallback("flow_ops.list_flows"),
        staticCallback("agent_ops.list_agents"),
        staticCallback("insight.recent_sessions"),
        staticCallback("insight.fetch_metrics")
    };
    Mockito.when(provider.getToolCallbacks()).thenReturn(callbacks);
    return provider;
  }

  private static ToolCallback staticCallback(String name) {
    ToolDefinition definition =
        DefaultToolDefinition.builder()
            .name(name)
            .description("Stub tool " + name)
            .inputSchema(DEFAULT_SCHEMA)
            .build();

    return new ToolCallback() {
      @Override
      public ToolDefinition getToolDefinition() {
        return definition;
      }

      @Override
      public String call(String toolInput) {
        return "{}";
      }
    };
  }
}
