package com.aiadvent.mcp.backend.coding;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class CodingToolConfiguration {

  @Bean
  ToolCallbackProvider codingToolCallbackProvider(CodingTools tools) {
    return MethodToolCallbackProvider.builder().toolObjects(tools).build();
  }
}
