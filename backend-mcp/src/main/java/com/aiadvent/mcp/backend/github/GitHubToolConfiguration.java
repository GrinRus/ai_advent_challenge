package com.aiadvent.mcp.backend.github;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class GitHubToolConfiguration {

  @Bean
  ToolCallbackProvider githubToolCallbackProvider(GitHubTools tools, GitHubWorkspaceTools workspaceTools) {
    return MethodToolCallbackProvider.builder().toolObjects(tools, workspaceTools).build();
  }
}
