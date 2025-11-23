package com.aiadvent.mcp.backend.github.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.aiadvent.mcp.backend.config.GitHubRagProperties;
import com.aiadvent.mcp.backend.github.rag.ast.AstFileContextFactory;
import com.aiadvent.mcp.backend.github.rag.ast.TreeSitterAnalyzer;
import com.aiadvent.mcp.backend.github.rag.ast.LanguageRegistry;
import com.aiadvent.mcp.backend.github.rag.ast.TreeSitterLibraryLoader;
import com.aiadvent.mcp.backend.github.rag.ast.TreeSitterParser;
import com.aiadvent.mcp.backend.github.rag.ast.TreeSitterQueryRegistry;
import com.aiadvent.mcp.backend.github.rag.chunking.RepoRagChunker;
import com.aiadvent.mcp.backend.github.rag.persistence.RepoRagFileStateRepository;
import com.aiadvent.mcp.backend.github.rag.persistence.RepoRagSymbolGraphRepository;
import com.aiadvent.mcp.backend.github.rag.persistence.RepoRagVectorStoreAdapter;
import com.aiadvent.mcp.backend.github.workspace.TempWorkspaceService;
import com.aiadvent.mcp.backend.github.workspace.TempWorkspaceService.Workspace;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.testcontainers.containers.Neo4jContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.springframework.ai.document.Document;

@Testcontainers
@ExtendWith(MockitoExtension.class)
class RepoRagIndexServiceNeo4jE2ETest {

  private static final String NAMESPACE = "repo:owner/java-demo";

  @Container
  private static final Neo4jContainer<?> neo4j =
      new Neo4jContainer<>("neo4j:5.19.0-community")
          .withAdminPassword("passw0rd");

  @Mock private TempWorkspaceService workspaceService;
  @Mock private RepoRagVectorStoreAdapter vectorStoreAdapter;
  @Mock private RepoRagFileStateRepository fileStateRepository;
  @Mock private RepoRagSymbolGraphRepository symbolGraphRepository;

  @TempDir Path tempDir;

  private RepoRagIndexService service;
  private GraphQueryService graphQueryService;
  private Driver driver;

  @BeforeEach
  void setUp() throws IOException {
    neo4j.start();
    driver = GraphDatabase.driver(neo4j.getBoltUrl(), AuthTokens.basic("neo4j", neo4j.getAdminPassword()));

    GitHubRagProperties properties = new GitHubRagProperties();
    properties.getChunking().getLine().setMaxLines(8);
    properties.getChunking().setOverlapLines(0);
    properties.getChunking().setStrategy(GitHubRagProperties.Strategy.SEMANTIC);
    properties.getChunking().getSemantic().setEnabled(true);
    properties.getAst().setEnabled(true);
    properties.getAst().setLanguages(List.of("java"));
    properties.getGraph().setEnabled(true);
    properties.getGraph().setUri(neo4j.getBoltUrl());
    properties.getGraph().setUsername("neo4j");
    properties.getGraph().setPassword(neo4j.getAdminPassword());
    properties.getGraph().setDatabase("neo4j");

    RepoRagChunker chunker = new RepoRagChunker(properties);
    TreeSitterAnalyzer analyzer = org.mockito.Mockito.mock(TreeSitterAnalyzer.class);
    when(analyzer.isEnabled()).thenReturn(true);
    when(analyzer.supportsLanguage(org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
    when(analyzer.ensureLanguageLoaded(org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
    when(analyzer.isNativeEnabled()).thenReturn(true);
    LanguageRegistry languageRegistry = org.mockito.Mockito.mock(LanguageRegistry.class);
    TreeSitterQueryRegistry queryRegistry = org.mockito.Mockito.mock(TreeSitterQueryRegistry.class);
    TreeSitterLibraryLoader loader = org.mockito.Mockito.mock(TreeSitterLibraryLoader.class);
    AstFileContextFactory astFactory =
        new AstFileContextFactory(
            languageRegistry, queryRegistry, new TreeSitterParser(loader, languageRegistry), analyzer);
    SymbolGraphWriter symbolGraphWriter =
        new SymbolGraphWriter(symbolGraphRepository, new SimpleMeterRegistry());
    GraphSyncService graphSyncService =
        new GraphSyncService(driver, properties, new SimpleMeterRegistry());
    graphQueryService = new GraphQueryService(driver, properties);

    service =
        new RepoRagIndexService(
            workspaceService,
            vectorStoreAdapter,
            fileStateRepository,
            chunker,
            properties,
            astFactory,
            symbolGraphWriter,
            graphSyncService);
  }

  @Test
  void indexesFixtureAndCreatesNeo4jEdges() throws IOException {
    Path fixtureRoot =
        Path.of("src", "test", "resources", "mini-repos", "java").toAbsolutePath();
    Path workspaceRoot = tempDir.resolve("java");
    copyFixture(fixtureRoot, workspaceRoot);

    Workspace workspace =
        new Workspace(
            "ws-java",
            workspaceRoot,
            Instant.now(),
            Instant.now().plusSeconds(600),
            "req-neo4j",
            "owner/java-demo",
            "refs/heads/main",
            0L,
            null,
            List.of(),
            null,
            null,
            null);
    when(workspaceService.findWorkspace("ws-java")).thenReturn(Optional.of(workspace));
    when(vectorStoreAdapter.listFilePaths(NAMESPACE)).thenReturn(new java.util.HashSet<>());
    when(fileStateRepository.findByNamespace(NAMESPACE)).thenReturn(List.of());
    when(fileStateRepository.save(org.mockito.ArgumentMatchers.any()))
        .thenAnswer(inv -> inv.getArgument(0));

    RepoRagIndexService.IndexResult result =
        service.indexWorkspace(
            new RepoRagIndexService.IndexRequest(
                "owner",
                "java-demo",
                "ws-java",
                NAMESPACE,
                "refs/heads/main",
                null,
                0L,
                Instant.now()));

    assertThat(result.astReady()).isTrue();

    long symbolCount =
        driver
            .session(
                org.neo4j.driver.SessionConfig.builder().withDatabase("neo4j").build())
            .executeWrite(
                tx ->
                    tx.run(
                            "MATCH (s:Symbol {namespace:$namespace}) RETURN count(s) AS cnt",
                            java.util.Map.of("namespace", NAMESPACE))
                        .single()
                        .get("cnt")
                        .asLong());

    assertThat(symbolCount).isGreaterThan(0);
  }

  private void copyFixture(Path source, Path target) throws IOException {
    Files.walkFileTree(
        source,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
              throws IOException {
            Path relative = source.relativize(dir);
            Files.createDirectories(target.resolve(relative));
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
            Path relative = source.relativize(file);
            Path dest = target.resolve(relative);
            Files.createDirectories(dest.getParent());
            Files.copy(file, dest, StandardCopyOption.REPLACE_EXISTING);
            return FileVisitResult.CONTINUE;
          }
        });
  }
}
