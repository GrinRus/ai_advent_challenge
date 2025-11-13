package com.aiadvent.mcp.backend;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Shared PostgreSQL Testcontainers instance used by integration tests that require pgvector.
 * Starts once per test JVM and registers datasource/env properties for profiles that expect
 * {@code GITHUB_MCP_*} and {@code NOTES_MCP_*} overrides.
 */
public final class PostgresTestContainer {

  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg15")
          .withDatabaseName("ai_advent_test")
          .withUsername("ai_advent")
          .withPassword("ai_advent");

  static {
    POSTGRES.start();
  }

  private PostgresTestContainer() {}

  public static void register(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");

    registry.add("GITHUB_MCP_DB_URL", POSTGRES::getJdbcUrl);
    registry.add("GITHUB_MCP_DB_USER", POSTGRES::getUsername);
    registry.add("GITHUB_MCP_DB_PASSWORD", POSTGRES::getPassword);

    registry.add("NOTES_MCP_DB_URL", POSTGRES::getJdbcUrl);
    registry.add("NOTES_MCP_DB_USER", POSTGRES::getUsername);
    registry.add("NOTES_MCP_DB_PASSWORD", POSTGRES::getPassword);
  }

  static PostgreSQLContainer<?> container() {
    return POSTGRES;
  }
}
