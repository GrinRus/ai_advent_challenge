package com.aiadvent.backend.mcp.insight;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class InsightToolConfiguration {

  @Bean
  ToolCallbackProvider insightToolCallbackProvider(InsightTools tools) {
    return MethodToolCallbackProvider.builder().toolObjects(tools).build();
  }
}
