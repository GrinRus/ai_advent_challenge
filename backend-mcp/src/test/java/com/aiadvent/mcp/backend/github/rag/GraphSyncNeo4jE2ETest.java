package com.aiadvent.mcp.backend.github.rag;

import static org.assertj.core.api.Assertions.assertThat;

import com.aiadvent.mcp.backend.config.GitHubRagProperties;
import com.aiadvent.mcp.backend.github.rag.chunking.AstSymbolMetadata;
import com.aiadvent.mcp.backend.github.rag.chunking.Chunk;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.testcontainers.containers.Neo4jContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GraphSyncNeo4jE2ETest {

  @Container
  private static final Neo4jContainer<?> neo4j =
      new Neo4jContainer<>("neo4j:5.19.0-community")
          .withAdminPassword("testpass");

  private Driver driver;

  @BeforeAll
  void setUp() {
    neo4j.start();
    driver = GraphDatabase.driver(neo4j.getBoltUrl(), AuthTokens.basic("neo4j", neo4j.getAdminPassword()));
  }

  @AfterAll
  void tearDown() {
    if (driver != null) {
      driver.close();
    }
    neo4j.stop();
  }

  @Test
  void syncsChunksAndReturnsNeighbors() {
    GitHubRagProperties properties = new GitHubRagProperties();
    properties.getGraph().setEnabled(true);
    properties.getGraph().setUri(neo4j.getBoltUrl());
    properties.getGraph().setUsername("neo4j");
    properties.getGraph().setPassword(neo4j.getAdminPassword());

    GraphSyncService syncService = new GraphSyncService(driver, properties, new SimpleMeterRegistry());
    GraphQueryService queryService = new GraphQueryService(driver, properties);

    AstSymbolMetadata serviceSymbol =
        new AstSymbolMetadata(
            "com.demo.Service#process",
            "method",
            "public",
            "process()",
            null,
            false,
            List.of(),
            List.of("com.demo.Helper#run"),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            3,
            10);
    AstSymbolMetadata helperSymbol =
        new AstSymbolMetadata(
            "com.demo.Helper#run",
            "method",
            "public",
            "run()",
            null,
            false,
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            12,
            20);
    Chunk serviceChunk =
        new Chunk(
            "class Service { void process() { Helper.run(); }}",
            1,
            10,
            "java",
            "Service chunk",
            "hash-service",
            null,
            0,
            serviceSymbol);
    Chunk helperChunk =
        new Chunk(
            "class Helper { void run() {} }",
            11,
            22,
            "java",
            "Helper chunk",
            "hash-helper",
            null,
            0,
            helperSymbol);

    String namespace = "repo:owner/repo";
    String filePath = "src/main/java/com/demo/Service.java";
    syncService.syncFile(namespace, filePath, List.of(serviceChunk, helperChunk));

    GraphQueryService.GraphNeighbors neighbors =
        queryService.neighbors(
            namespace,
            "com.demo.Service#process",
            GraphQueryService.Direction.OUTGOING,
            java.util.Set.of("CALLS"),
            10);

    assertThat(neighbors.nodes())
        .extracting(GraphQueryService.GraphNode::fqn)
        .contains("com.demo.Service#process", "com.demo.Helper#run");
    assertThat(neighbors.edges())
        .anySatisfy(
            edge -> {
              assertThat(edge.relation()).isEqualTo("CALLS");
              assertThat(edge.from()).isEqualTo("com.demo.Service#process");
              assertThat(edge.to()).isEqualTo("com.demo.Helper#run");
              assertThat(edge.chunkHash()).isEqualTo("hash-service");
            });
  }
}
