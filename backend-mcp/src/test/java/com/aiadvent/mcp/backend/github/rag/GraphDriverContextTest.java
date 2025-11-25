package com.aiadvent.mcp.backend.github.rag;

import static org.assertj.core.api.Assertions.assertThat;

import com.aiadvent.mcp.backend.McpApplication;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.neo4j.driver.Driver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.Neo4jContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Boots the full github-mcp Spring context and verifies that graph driver wiring works when
 * github.rag.graph.enabled=true (mirrors prod layout).
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
    classes = McpApplication.class,
    properties = {
      "spring.main.web-application-type=none",
      "spring.liquibase.enabled=true",
      "spring.jpa.hibernate.ddl-auto=none",
      "spring.jpa.properties.hibernate.hbm2ddl.auto=none",
      "spring.sql.init.mode=never",
      "spring.ai.openai.api-key=dummy",
      "spring.ai.openai.base-url=https://api.openai.com/v1",
      "github.backend.base-url=https://api.github.com",
      "github.backend.personal-access-token=dummy-token"
    })
@ActiveProfiles("github")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GraphDriverContextTest {

  @Container
  static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("pgvector/pgvector:pg15")
          .withDatabaseName("ai_advent_test")
          .withUsername("ai_advent")
          .withPassword("ai_advent");

  @Container
  static final Neo4jContainer<?> neo4j =
      new Neo4jContainer<>("neo4j:5.19.0-community").withAdminPassword("graph-pass");

  static {
    postgres.start();
    neo4j.start();
  }

  @DynamicPropertySource
  static void overrideProps(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
    registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");

    registry.add("github.rag.graph.enabled", () -> true);
    registry.add("github.rag.graph.uri", neo4j::getBoltUrl);
    registry.add("github.rag.graph.username", () -> "neo4j");
    registry.add("github.rag.graph.password", neo4j::getAdminPassword);
    registry.add("github.rag.graph.database", () -> "neo4j");

    registry.add("spring.neo4j.uri", neo4j::getBoltUrl);
    registry.add("spring.neo4j.authentication.username", () -> "neo4j");
    registry.add("spring.neo4j.authentication.password", neo4j::getAdminPassword);
    registry.add("spring.neo4j.database", () -> "neo4j");
  }

  @Autowired private Driver driver;
  @Autowired private GraphSyncService graphSyncService;

  @Test
  void graphBeansPresentInGithubProfile() {
    assertThat(driver).isNotNull();
    assertThat(graphSyncService).isNotNull();
  }
}
