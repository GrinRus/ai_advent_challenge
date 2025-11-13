package com.aiadvent.mcp.backend;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(classes = McpApplication.class, properties = "spring.profiles.active=github")
class GithubMcpApplicationTests {

  @DynamicPropertySource
  static void overrideProperties(DynamicPropertyRegistry registry) {
    PostgresTestContainer.register(registry);
  }

  @Test
  void contextLoads() {}
}
