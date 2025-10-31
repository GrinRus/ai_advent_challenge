package com.aiadvent.mcp.backend.docker;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class DockerRunnerToolConfiguration {

  @Bean
  ToolCallbackProvider dockerRunnerToolCallbackProvider(DockerRunnerTools tools) {
    return MethodToolCallbackProvider.builder().toolObjects(tools).build();
  }
}

