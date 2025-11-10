package com.aiadvent.mcp.backend.github.rag;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RepoRagToolConfiguration {

  @Bean
  ToolCallbackProvider repoRagToolCallbackProvider(RepoRagTools repoRagTools) {
    return MethodToolCallbackProvider.builder().toolObjects(repoRagTools).build();
  }
}
