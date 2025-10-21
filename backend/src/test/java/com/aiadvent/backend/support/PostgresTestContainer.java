package com.aiadvent.backend.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

public abstract class PostgresTestContainer {

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
  }
}
