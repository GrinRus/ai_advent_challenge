package com.aiadvent.mcp.backend.config;

import java.util.concurrent.TimeUnit;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Config;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Configuration
@ConditionalOnProperty(prefix = "github.rag.graph", name = "enabled", havingValue = "true", matchIfMissing = false)
@ConditionalOnBean(GitHubRagProperties.class)
public class GraphDriverConfiguration {

  private static final Logger log = LoggerFactory.getLogger(GraphDriverConfiguration.class);

  @Bean(destroyMethod = "close")
  public Driver graphDriver(GitHubRagProperties properties) {
    GitHubRagProperties.Graph graph = properties.getGraph();
    String username = StringUtils.hasText(graph.getUsername()) ? graph.getUsername() : "neo4j";
    String password = graph.getPassword() != null ? graph.getPassword() : "";
    Config driverConfig =
        Config.builder()
            .withMaxConnectionPoolSize(30)
            .withConnectionAcquisitionTimeout(graph.getSyncTimeout().toMillis(), TimeUnit.MILLISECONDS)
            .build();
    log.info(
        "Creating Neo4j driver (enabled={}, uri={}, database={})",
        graph.isEnabled(),
        graph.getUri(),
        graph.getDatabase());
    return GraphDatabase.driver(graph.getUri(), AuthTokens.basic(username, password), driverConfig);
  }
}
