package com.aiadvent.backend.support;

import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@Import({TestTokenUsageConfiguration.class, TestMcpToolCallbackConfiguration.class})
public abstract class PostgresTestContainer {

  private static final AtomicBoolean REDIS_ENABLED = new AtomicBoolean(false);

  static {
    System.setProperty("app.chat.token-usage.lightweight-mode", "true");
    // Disable MCP client startup in tests; some environments set PERPLEXITY_MCP_ENABLED=true which
    // would otherwise trigger the external lifecycle initializer and time out.
    System.setProperty("spring.ai.mcp.client.enabled", "false");
  }

  private static final SingletonPostgresContainer POSTGRES =
      SingletonPostgresContainer.getInstance();

  protected static void setRedisEnabled(boolean enabled) {
    REDIS_ENABLED.set(enabled);
  }

  private static String jdbcUrlWithSslDisabled() {
    String url = POSTGRES.getJdbcUrl();
    if (url.contains("sslmode=")) {
      return url;
    }
    String separator = url.contains("?") ? "&" : "?";
    return url + separator + "sslmode=disable";
  }

  @DynamicPropertySource
  static void configure(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", PostgresTestContainer::jdbcUrlWithSslDisabled);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
    registry.add("spring.liquibase.url", PostgresTestContainer::jdbcUrlWithSslDisabled);
    registry.add("spring.liquibase.user", POSTGRES::getUsername);
    registry.add("spring.liquibase.password", POSTGRES::getPassword);
    registry.add("spring.ai.openai.api-key", () -> "test-key");
    registry.add("spring.ai.openai.base-url", () -> "https://example.invalid");
    registry.add("spring.ai.openai.chat.options.model", () -> "gpt-4o-mini");
    registry.add("spring.datasource.hikari.maximum-pool-size", () -> "4");
    registry.add("spring.datasource.hikari.minimum-idle", () -> "1");
    registry.add("app.flow.worker.enabled", () -> "false");
    registry.add("app.chat.token-usage.lightweight-mode", () -> "true");
    registry.add("app.profile.cache.redis-enabled", () -> Boolean.toString(REDIS_ENABLED.get()));
  }
}
