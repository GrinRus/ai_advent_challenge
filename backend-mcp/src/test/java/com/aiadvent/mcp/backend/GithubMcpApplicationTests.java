package com.aiadvent.mcp.backend;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.EnabledIf;

@SpringBootTest(classes = McpApplication.class, properties = "spring.profiles.active=github")
@EnabledIf(
    expression = "#{T(com.aiadvent.mcp.backend.PostgresTestContainer).dockerAvailable()}",
    reason = "Docker is required for Postgres-backed tests",
    loadContext = false)
class GithubMcpApplicationTests {

  @DynamicPropertySource
  static void overrideProperties(DynamicPropertyRegistry registry) {
    PostgresTestContainer.register(registry);
  }

  @Test
  void contextLoads() {}
}
