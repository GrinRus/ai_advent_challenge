package com.aiadvent.mcp.backend.github.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.aiadvent.mcp.backend.config.GitHubRagProperties;
import com.aiadvent.mcp.backend.github.rag.chunking.AstSymbolMetadata;
import com.aiadvent.mcp.backend.github.rag.chunking.Chunk;
import com.aiadvent.mcp.backend.github.rag.persistence.RepoRagNamespaceStateEntity;
import com.aiadvent.mcp.backend.github.rag.postprocessing.RepoRagPostProcessingRequest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.testcontainers.containers.Neo4jContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@ExtendWith(MockitoExtension.class)
@Testcontainers
class RepoRagSearchServiceGraphIntegrationTest {

  @Container
  static final Neo4jContainer<?> neo4j =
      new Neo4jContainer<>("neo4j:5.19.0-community").withAdminPassword("smoke-pass");

  @Mock private RepoRagRetrievalPipeline retrievalPipeline;
  @Mock private RepoRagSearchReranker reranker;
  @Mock private RepoRagGenerationService generationService;
  @Mock private RepoRagNamespaceStateService namespaceStateService;

  private Driver driver;
  private GitHubRagProperties properties;
  private GraphQueryService graphQueryService;
  private GraphSyncService graphSyncService;

  @BeforeEach
  void setUp() {
    neo4j.start();
    driver =
        GraphDatabase.driver(neo4j.getBoltUrl(), AuthTokens.basic("neo4j", neo4j.getAdminPassword()));
    properties = new GitHubRagProperties();
    properties.getGraph().setEnabled(true);
    properties.getGraph().setUri(neo4j.getBoltUrl());
    properties.getGraph().setUsername("neo4j");
    properties.getGraph().setPassword(neo4j.getAdminPassword());
    properties.getGraph().setDatabase("neo4j");
    graphSyncService = new GraphSyncService(driver, properties, new SimpleMeterRegistry());
    graphQueryService = new GraphQueryService(driver, properties);
  }

  @AfterEach
  void tearDown() {
    if (driver != null) {
      driver.close();
    }
    neo4j.stop();
  }

  @Test
  void searchResponseContainsGraphNeighborsFromNeo4j() {
    String namespace = "repo:owner/demo";
    syncGraph(namespace);

    RepoRagSearchService service =
        new RepoRagSearchService(
            properties, retrievalPipeline, reranker, generationService, namespaceStateService, graphQueryService);

    RepoRagNamespaceStateEntity state = readyState(namespace);
    when(namespaceStateService.findByRepoOwnerAndRepoName("owner", "demo"))
        .thenReturn(java.util.Optional.of(state));

    Query baseQuery = Query.builder().text("find helper").history(List.of()).build();
    when(retrievalPipeline.buildQuery(anyString(), anyList(), any(), anyInt())).thenReturn(baseQuery);

    Document doc =
        Document.builder()
            .id("chunk-1")
            .text("class Service { void process() { helper(); } }")
            .metadata(
                Map.of(
                    "file_path", "src/main/java/com/demo/Service.java",
                    "symbol_fqn", "com.demo.Service#process",
                    "summary", "process method"))
            .score(0.9)
            .build();
    RepoRagRetrievalPipeline.PipelineResult pipelineResult =
        new RepoRagRetrievalPipeline.PipelineResult(baseQuery, List.of(doc), List.of(), List.of(baseQuery));
    when(retrievalPipeline.execute(any())).thenReturn(pipelineResult);

    RepoRagSearchReranker.PostProcessingResult postProcessingResult =
        new RepoRagSearchReranker.PostProcessingResult(List.of(doc), false, List.of());
    when(reranker.process(any(), anyList(), any(RepoRagPostProcessingRequest.class)))
        .thenReturn(postProcessingResult);

    when(generationService.generate(any()))
        .thenReturn(
            new RepoRagGenerationService.GenerationResult(
                "raw", "summary", false, null, List.of(), "summary", "raw"));

    RepoRagSearchService.SearchCommand command =
        new RepoRagSearchService.SearchCommand(
            "owner",
            "demo",
            "find helper",
            plan("balanced"),
            List.of(),
            null,
            RepoRagResponseChannel.BOTH);

    RepoRagSearchService.SearchResponse response = service.search(command);

    assertThat(response.matches()).hasSize(1);
    Map<String, Object> metadata = response.matches().get(0).metadata();
    assertThat(metadata.get("graph_neighbors")).isNotNull();
    assertThat(metadata.get("graph_path")).isNotNull();
    assertThat(response.appliedModules()).contains("graph.lens");
  }

  private void syncGraph(String namespace) {
    AstSymbolMetadata serviceSymbol =
        new AstSymbolMetadata(
            "com.demo.Service#process",
            "method",
            "public",
            "void process()",
            null,
            false,
            List.of(),
            List.of("com.demo.Helper#run"),
            List.of(),
            List.of(),
            List.of(),
            Set.of(),
            3,
            12);
    AstSymbolMetadata helperSymbol =
        new AstSymbolMetadata(
            "com.demo.Helper#run",
            "method",
            "public",
            "void run()",
            null,
            false,
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            Set.of(),
            20,
            32);
    Chunk serviceChunk =
        new Chunk(
            "class Service { void process() { Helper.run(); }}",
            1,
            12,
            "java",
            "service",
            "hash-service",
            null,
            0,
            serviceSymbol);
    Chunk helperChunk =
        new Chunk(
            "class Helper { void run() {} }",
            13,
            32,
            "java",
            "helper",
            "hash-helper",
            null,
            0,
            helperSymbol);
    graphSyncService.syncFile(namespace, "src/main/java/com/demo/Service.java", List.of(serviceChunk, helperChunk));
  }

  private RepoRagNamespaceStateEntity readyState(String namespace) {
    RepoRagNamespaceStateEntity entity = new RepoRagNamespaceStateEntity();
    entity.setNamespace(namespace);
    entity.setRepoOwner("owner");
    entity.setRepoName("demo");
    entity.setReady(true);
    entity.setAstSchemaVersion(RepoRagIndexService.AST_VERSION);
    entity.setAstReadyAt(Instant.now());
    entity.setGraphReady(true);
    entity.setGraphSchemaVersion(1);
    entity.setGraphReadyAt(Instant.now());
    return entity;
  }

  private RagParameterGuard.ResolvedSearchPlan plan(String name) {
    return new RagParameterGuard.ResolvedSearchPlan(
        name,
        8,
        8,
        0.6d,
        Map.of(),
        8,
        true,
        2.0d,
        new RepoRagMultiQueryOptions(true, 3, 3),
        new RagParameterGuard.NeighborOptions("CALL_GRAPH", 3, 8),
        RagParameterGuard.SearchPlanMode.STANDARD,
        0.4d,
        "overview",
        List.of());
  }
}
