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

@Configuration
@ConditionalOnProperty(prefix = "github.rag.graph", name = "enabled", havingValue = "true", matchIfMissing = false)
@ConditionalOnBean(GitHubRagProperties.class)
public class GraphDriverConfiguration {

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
    return GraphDatabase.driver(graph.getUri(), AuthTokens.basic(username, password), driverConfig);
  }
}
