package com.aiadvent.mcp.backend.github.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.aiadvent.mcp.backend.config.GitHubRagProperties;
import com.aiadvent.mcp.backend.github.rag.ast.AstFileContextFactory;
import com.aiadvent.mcp.backend.github.rag.ast.TreeSitterAnalyzer;
import com.aiadvent.mcp.backend.github.rag.ast.LanguageRegistry;
import com.aiadvent.mcp.backend.github.rag.ast.TreeSitterQueryRegistry;
import com.aiadvent.mcp.backend.github.rag.ast.TreeSitterLibraryLoader;
import com.aiadvent.mcp.backend.github.rag.ast.TreeSitterParser;
import com.aiadvent.mcp.backend.github.rag.chunking.RepoRagChunker;
import com.aiadvent.mcp.backend.github.rag.persistence.RepoRagFileStateRepository;
import com.aiadvent.mcp.backend.github.rag.persistence.RepoRagSymbolGraphRepository;
import com.aiadvent.mcp.backend.github.rag.persistence.RepoRagVectorStoreAdapter;
import com.aiadvent.mcp.backend.github.workspace.TempWorkspaceService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.SessionConfig;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.Neo4jContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@ExtendWith(MockitoExtension.class)
@Testcontainers
class RepoRagNativeGraphSmokeTest {

  @Container
  static final Neo4jContainer<?> neo4j =
      new Neo4jContainer<>("neo4j:5.19.0-community").withAdminPassword("testpass");

  @Mock private RepoRagVectorStoreAdapter vectorStoreAdapter;
  @Mock private RepoRagFileStateRepository fileStateRepository;
  @Mock private RepoRagSymbolGraphRepository symbolGraphRepository;
  @Mock private TempWorkspaceService workspaceService;

  private static Driver driver;

  @BeforeAll
  static void startNeo4j() {
    assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker not available");
    neo4j.start();
    driver =
        GraphDatabase.driver(
            neo4j.getBoltUrl(), AuthTokens.basic("neo4j", neo4j.getAdminPassword()));
  }

  @AfterAll
  static void stopNeo4j() {
    if (driver != null) {
      driver.close();
    }
    neo4j.stop();
  }

  @Test
  void indexesJavaAndStoresCallEdgesInNeo4j() throws Exception {
    GitHubRagProperties properties = new GitHubRagProperties();
    properties.getAst().setEnabled(true);
    properties.getAst().setNativeEnabled(true);
    properties.getAst().setLanguages(List.of("java"));
    properties.getGraph().setEnabled(true);
    properties.getGraph().setUri(neo4j.getBoltUrl());
    properties.getGraph().setUsername("neo4j");
    properties.getGraph().setPassword(neo4j.getAdminPassword());

    Path workspaceRoot = Files.createTempDirectory("repo-smoke");
    Path src = workspaceRoot.resolve("src/main/java/com/example");
    Files.createDirectories(src);
    Files.writeString(
        src.resolve("DemoService.java"),
        "package com.example;\n"
            + "public class DemoService {\n"
            + "  public void run() { helper(); }\n"
            + "  private void helper() {}\n"
            + "}\n");

    TempWorkspaceService.Workspace workspace =
        new TempWorkspaceService.Workspace(
            "ws-smoke",
            workspaceRoot,
            Instant.now(),
            Instant.now().plusSeconds(600),
            UUID.randomUUID().toString(),
            "owner/java-demo",
            "refs/heads/main",
            0L,
            null,
            List.of(),
            null,
            null,
            null);
    org.mockito.Mockito.when(workspaceService.findWorkspace("ws-smoke"))
        .thenReturn(Optional.of(workspace));
    org.mockito.Mockito.when(vectorStoreAdapter.listFilePaths("repo:owner/java-demo"))
        .thenReturn(new java.util.HashSet<>());
    org.mockito.Mockito.when(fileStateRepository.findByNamespace("repo:owner/java-demo"))
        .thenReturn(List.of());

    TreeSitterLibraryLoader loader =
        new TreeSitterLibraryLoader(properties, new org.springframework.core.io.DefaultResourceLoader());
    assumeTrue(loader.ensureCoreLibraryLoaded(), "libjava-tree-sitter not available for current arch");
    TreeSitterAnalyzer analyzer = new TreeSitterAnalyzer(properties, loader);
    RepoRagChunker chunker = new RepoRagChunker(properties);
    LanguageRegistry languageRegistry = new LanguageRegistry(loader);
    TreeSitterQueryRegistry queryRegistry = new TreeSitterQueryRegistry();
    AstFileContextFactory astFactory =
        new AstFileContextFactory(
            languageRegistry, queryRegistry, new TreeSitterParser(loader, languageRegistry), analyzer);
    SymbolGraphWriter symbolGraphWriter = new SymbolGraphWriter(symbolGraphRepository, new SimpleMeterRegistry());
    GraphSyncService graphSyncService = new GraphSyncService(driver, properties, new SimpleMeterRegistry());

    RepoRagIndexService service =
        new RepoRagIndexService(
            workspaceService,
            vectorStoreAdapter,
            fileStateRepository,
            chunker,
            properties,
            astFactory,
            symbolGraphWriter,
            graphSyncService);

    RepoRagIndexService.IndexResult result =
        service.indexWorkspace(
            new RepoRagIndexService.IndexRequest(
                "owner",
                "java-demo",
                "ws-smoke",
                "repo:owner/java-demo",
                "refs/heads/main",
                null,
                0L,
                Instant.now()));

    assertThat(result.astReady()).isTrue();

    long edgeCount =
        driver
            .session(SessionConfig.builder().withDatabase("neo4j").build())
            .executeRead(
                tx ->
                    tx.run("MATCH (:Symbol)-[r:CALLS]->(:Symbol) RETURN count(r) AS c")
                        .single()
                        .get("c")
                        .asLong());

    assertThat(edgeCount).isGreaterThan(0);
  }
}
