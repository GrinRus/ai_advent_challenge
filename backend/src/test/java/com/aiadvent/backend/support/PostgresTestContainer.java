package com.aiadvent.backend.support;

import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@Import(TestTokenUsageConfiguration.class)
public abstract class PostgresTestContainer {
  static {
    System.setProperty("app.chat.token-usage.lightweight-mode", "true");
  }

  private static final SingletonPostgresContainer POSTGRES =
      SingletonPostgresContainer.getInstance();

  @DynamicPropertySource
  static void configure(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
    registry.add("spring.liquibase.url", POSTGRES::getJdbcUrl);
    registry.add("spring.liquibase.user", POSTGRES::getUsername);
    registry.add("spring.liquibase.password", POSTGRES::getPassword);
    registry.add("spring.ai.openai.api-key", () -> "test-key");
    registry.add("spring.ai.openai.base-url", () -> "https://example.invalid");
    registry.add("spring.ai.openai.chat.options.model", () -> "gpt-4o-mini");
    registry.add("spring.datasource.hikari.maximum-pool-size", () -> "4");
    registry.add("spring.datasource.hikari.minimum-idle", () -> "1");
    registry.add("app.flow.worker.enabled", () -> "false");
    registry.add("app.chat.token-usage.lightweight-mode", () -> "true");
  }
}
