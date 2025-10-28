package com.aiadvent.mcp.backend.flowops;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class FlowOpsToolConfiguration {

  @Bean
  ToolCallbackProvider flowOpsToolCallbackProvider(FlowOpsTools tools) {
    return MethodToolCallbackProvider.builder().toolObjects(tools).build();
  }
}
