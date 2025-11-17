package com.aiadvent.mcp.backend;

import org.junit.jupiter.api.Assumptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Shared PostgreSQL Testcontainers instance used by integration tests that require pgvector.
 * Starts once per test JVM (when Docker is available) and registers datasource/env properties for
 * profiles that expect {@code GITHUB_MCP_*} and {@code NOTES_MCP_*} overrides.
 */
public final class PostgresTestContainer {

  private static final Logger log = LoggerFactory.getLogger(PostgresTestContainer.class);

  private static final boolean DOCKER_AVAILABLE = isDockerAvailable();
  private static final PostgreSQLContainer<?> POSTGRES = startContainer();

  private PostgresTestContainer() {}

  public static void assumeDockerAvailable() {
    Assumptions.assumeTrue(
        DOCKER_AVAILABLE, "Docker is required to run Postgres-backed integration tests");
  }

  public static void register(DynamicPropertyRegistry registry) {
    if (!DOCKER_AVAILABLE || POSTGRES == null) {
      throw new IllegalStateException("Docker is required to configure Postgres test container");
    }
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
    if (!DOCKER_AVAILABLE || POSTGRES == null) {
      throw new IllegalStateException("Docker is required to access the shared Postgres container");
    }
    return POSTGRES;
  }

  private static PostgreSQLContainer<?> startContainer() {
    if (!DOCKER_AVAILABLE) {
      return null;
    }
    PostgreSQLContainer<?> container =
        new PostgreSQLContainer<>("pgvector/pgvector:pg15")
            .withDatabaseName("ai_advent_test")
            .withUsername("ai_advent")
            .withPassword("ai_advent");
    container.start();
    return container;
  }

  private static boolean isDockerAvailable() {
    try {
      DockerClientFactory.instance().client();
      return true;
    } catch (Throwable ex) {
      log.warn("Docker is not available for Testcontainers: {}", ex.getMessage());
      return false;
    }
  }
}
