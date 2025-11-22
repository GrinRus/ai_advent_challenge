package com.aiadvent.mcp.backend.github.rag;

import static org.assertj.core.api.Assertions.assertThat;

import com.aiadvent.mcp.backend.config.GitHubRagProperties;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.SessionConfig;
import org.testcontainers.containers.Neo4jContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@Disabled("Requires Docker/Neo4j; skip in local Java 22 bump")
class GraphQueryServiceTest {

  private static final String NAMESPACE = "repo:owner/demo";

  @Container
  private static final Neo4jContainer<?> neo4j =
      new Neo4jContainer<>("neo4j:5.19.0-community").withAdminPassword("pass");

  private static Driver driver;
  private static GraphQueryService service;

  @BeforeAll
  static void setUp() {
    neo4j.start();
    driver = GraphDatabase.driver(neo4j.getBoltUrl(), AuthTokens.basic("neo4j", neo4j.getAdminPassword()));
    GitHubRagProperties props = new GitHubRagProperties();
    props.getGraph().setDatabase("neo4j");
    props.getGraph().setEnabled(true);
    props.getGraph().setUri(neo4j.getBoltUrl());
    props.getGraph().setUsername("neo4j");
    props.getGraph().setPassword(neo4j.getAdminPassword());
    service = new GraphQueryService(driver, props);

    var session = driver.session(SessionConfig.builder().withDatabase("neo4j").build());
    session.executeWrite(
        tx -> {
          tx.run(
              """
              MERGE (repo:Repo {namespace:$ns})
              MERGE (repo)-[:CONTAINS]->(file:File {namespace:$ns, path:'src/Main.java'})
              MERGE (a:Symbol {namespace:$ns, fqn:'com.demo.A#foo', symbolKind:'method', filePath:'src/Main.java', lineStart:1, lineEnd:5})
              MERGE (b:Symbol {namespace:$ns, fqn:'com.demo.B#bar', symbolKind:'method', filePath:'src/Main.java', lineStart:7, lineEnd:12})
              MERGE (file)-[:DECLARES]->(a)
              MERGE (file)-[:DECLARES]->(b)
              MERGE (a)-[:CALLS {namespace:$ns, chunkHash:'hash-a', chunkIndex:0}]->(b)
              """,
              java.util.Map.of("ns", NAMESPACE));
          return null;
        });
  }

  @AfterAll
  static void tearDown() {
    if (driver != null) {
      driver.close();
    }
    neo4j.stop();
  }

  @Test
  void returnsNeighborsWithEdgeMetadata() {
    GraphQueryService.GraphNeighbors neighbors =
        service.neighbors(
            NAMESPACE,
            "com.demo.A#foo",
            GraphQueryService.Direction.OUTGOING,
            Set.of("CALLS"),
            10);

    assertThat(neighbors.nodes())
        .extracting(GraphQueryService.GraphNode::fqn)
        .contains("com.demo.A#foo", "com.demo.B#bar");
    assertThat(neighbors.edges())
        .anySatisfy(
            edge -> {
              assertThat(edge.relation()).isEqualTo("CALLS");
              assertThat(edge.chunkHash()).isEqualTo("hash-a");
              assertThat(edge.chunkIndex()).isEqualTo(0);
            });
  }

  @Test
  void returnsShortestPathBetweenSymbols() {
    GraphQueryService.GraphNeighbors path =
        service.shortestPath(
            NAMESPACE,
            "com.demo.A#foo",
            "com.demo.B#bar",
            Set.of("CALLS"),
            4);

    assertThat(path.edges()).isNotEmpty();
    assertThat(path.nodes())
        .extracting(GraphQueryService.GraphNode::fqn)
        .contains("com.demo.A#foo", "com.demo.B#bar");
  }

  @Test
  void returnsDefinition() {
    GraphQueryService.GraphNode node =
        service.definition(NAMESPACE, "com.demo.A#foo");

    assertThat(node).isNotNull();
    assertThat(node.filePath()).isEqualTo("src/Main.java");
    assertThat(node.kind()).isEqualTo("method");
    assertThat(node.lineStart()).isEqualTo(1);
  }
}
