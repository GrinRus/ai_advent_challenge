package com.aiadvent.backend.mcp.agentops;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class AgentOpsToolConfiguration {

  @Bean
  ToolCallbackProvider agentOpsToolCallbackProvider(AgentOpsTools tools) {
    return MethodToolCallbackProvider.builder().toolObjects(tools).build();
  }
}
