package com.aiadvent.mcp.backend.github.rag;

import static org.assertj.core.api.Assertions.assertThat;

import com.aiadvent.mcp.backend.config.GitHubRagProperties;
import com.aiadvent.mcp.backend.config.GraphDriverConfiguration;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.AfterAll;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.testcontainers.containers.Neo4jContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@SpringJUnitConfig(classes = GraphDriverContextTest.TestConfig.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GraphDriverContextTest {

  @Container
  static final Neo4jContainer<?> neo4j =
      new Neo4jContainer<>("neo4j:5.19.0-community").withAdminPassword("graph-pass");
  static {
    neo4j.start();
  }

  @Autowired private Driver driver;
  @Autowired private GraphSyncService graphSyncService;

  @Test
  void driverAndGraphSyncServiceArePresent() {
    assertThat(driver).isNotNull();
    assertThat(graphSyncService).isNotNull();
    try (Session session = driver.session(SessionConfig.forDatabase("neo4j"))) {
      Integer value = session.run("RETURN 1 AS n").single().get("n").asInt();
      assertThat(value).isEqualTo(1);
    }
  }

  @AfterAll
  void stopContainer() {
    if (neo4j != null && neo4j.isRunning()) {
      neo4j.stop();
    }
  }

  @Configuration
  static class TestConfig {
    @Bean
    GitHubRagProperties gitHubRagProperties() {
      GitHubRagProperties props = new GitHubRagProperties();
      GitHubRagProperties.Graph graph = props.getGraph();
      graph.setEnabled(true);
      graph.setUri(neo4j.getBoltUrl());
      graph.setUsername("neo4j");
      graph.setPassword(neo4j.getAdminPassword());
      graph.setDatabase("neo4j");
      return props;
    }

    @Bean(destroyMethod = "close")
    Driver driver(GitHubRagProperties props) {
      GraphDriverConfiguration cfg = new GraphDriverConfiguration();
      return cfg.graphDriver(props);
    }

    @Bean
    MeterRegistry meterRegistry() {
      return new SimpleMeterRegistry();
    }

    @Bean
    GraphSyncService graphSyncService(Driver driver, GitHubRagProperties props, MeterRegistry registry) {
      return new GraphSyncService(driver, props, registry);
    }
  }
}
