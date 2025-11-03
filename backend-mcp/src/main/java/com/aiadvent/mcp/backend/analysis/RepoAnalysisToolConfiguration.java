package com.aiadvent.mcp.backend.analysis;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class RepoAnalysisToolConfiguration {

  @Bean
  ToolCallbackProvider repoAnalysisToolCallbackProvider(RepoAnalysisTools tools) {
    return MethodToolCallbackProvider.builder().toolObjects(tools).build();
  }
}
