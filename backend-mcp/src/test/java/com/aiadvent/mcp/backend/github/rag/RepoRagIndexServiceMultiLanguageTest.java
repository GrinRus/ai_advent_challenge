package com.aiadvent.mcp.backend.github.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aiadvent.mcp.backend.config.GitHubRagProperties;
import com.aiadvent.mcp.backend.github.rag.ast.AstFileContextFactory;
import com.aiadvent.mcp.backend.github.rag.ast.LanguageRegistry;
import com.aiadvent.mcp.backend.github.rag.ast.TreeSitterLibraryLoader;
import com.aiadvent.mcp.backend.github.rag.ast.TreeSitterAnalyzer;
import com.aiadvent.mcp.backend.github.rag.ast.TreeSitterParser;
import com.aiadvent.mcp.backend.github.rag.ast.TreeSitterQueryRegistry;
import com.aiadvent.mcp.backend.github.rag.chunking.RepoRagChunker;
import com.aiadvent.mcp.backend.github.rag.persistence.RepoRagFileStateRepository;
import com.aiadvent.mcp.backend.github.rag.persistence.RepoRagSymbolGraphEntity;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.ai.document.Document;

@ExtendWith(MockitoExtension.class)
class RepoRagIndexServiceMultiLanguageTest {

  @Mock private TempWorkspaceService workspaceService;
  @Mock private RepoRagVectorStoreAdapter vectorStoreAdapter;
  @Mock private RepoRagFileStateRepository fileStateRepository;
  @Mock private RepoRagSymbolGraphRepository symbolGraphRepository;
  @Mock private GraphSyncService graphSyncService;

  @TempDir Path tempDir;

  private RepoRagIndexService service;

  @BeforeEach
  void setUp() {
    GitHubRagProperties properties = new GitHubRagProperties();
    properties.getChunking().getLine().setMaxLines(4);
    properties.getChunking().setOverlapLines(0);
    properties.getChunking().setStrategy(GitHubRagProperties.Strategy.SEMANTIC);
    properties.getChunking().getSemantic().setEnabled(true);
    properties.getAst().setEnabled(true);
    properties.getAst().setLanguages(List.of("java", "typescript", "python", "go", "kotlin", "javascript"));

    RepoRagChunker chunker = new RepoRagChunker(properties);
    TreeSitterAnalyzer analyzer = mock(TreeSitterAnalyzer.class);
    when(analyzer.isEnabled()).thenReturn(true);
    when(analyzer.supportsLanguage(anyString())).thenReturn(true);
    when(analyzer.ensureLanguageLoaded(anyString())).thenReturn(true);
    when(analyzer.isNativeEnabled()).thenReturn(false);
    LanguageRegistry languageRegistry = mock(LanguageRegistry.class);
    TreeSitterQueryRegistry queryRegistry = mock(TreeSitterQueryRegistry.class);
    TreeSitterLibraryLoader loader = mock(TreeSitterLibraryLoader.class);
    AstFileContextFactory astFactory =
        new AstFileContextFactory(
            languageRegistry, queryRegistry, new TreeSitterParser(loader, languageRegistry), analyzer);

    SymbolGraphWriter symbolGraphWriter =
        new SymbolGraphWriter(symbolGraphRepository, new SimpleMeterRegistry());

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

  @ParameterizedTest
  @MethodSource("fixtures")
  void indexesMiniRepoWithAstMetadata(MiniRepoFixture fixture) throws IOException {
    Path workspaceRoot = copyFixtureToWorkspace(fixture.fixtureName());
    String namespace = "repo:%s/%s".formatted(fixture.repoOwner(), fixture.repoName());
    when(workspaceService.findWorkspace(fixture.workspaceId()))
        .thenReturn(Optional.of(workspaceFor(workspaceRoot, fixture)));
    when(vectorStoreAdapter.listFilePaths(namespace)).thenReturn(new HashSet<>());
    when(fileStateRepository.findByNamespace(namespace)).thenReturn(List.of());

    RepoRagIndexService.IndexResult result =
        service.indexWorkspace(
            new RepoRagIndexService.IndexRequest(
                fixture.repoOwner(),
                fixture.repoName(),
                fixture.workspaceId(),
                namespace,
                "refs/heads/main",
                null,
                0L,
                Instant.now()));

    assertThat(result.astReady()).isTrue();

    ArgumentCaptor<String> pathCaptor = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<List<Document>> docCaptor = ArgumentCaptor.forClass(List.class);
    verify(vectorStoreAdapter, org.mockito.Mockito.atLeastOnce())
        .replaceFile(eq(namespace), pathCaptor.capture(), docCaptor.capture());
    int docIndex = pathCaptor.getAllValues().lastIndexOf(fixture.relativeFile());
    assertThat(docIndex).isGreaterThanOrEqualTo(0);
    List<Document> documents = docCaptor.getAllValues().get(docIndex);
    assertThat(documents).isNotEmpty();

    Document target =
        documents.stream()
            .filter(doc -> {
              Map<String, Object> metadata = doc.getMetadata();
              Object astAvailable = metadata != null ? metadata.get("ast_available") : null;
              return astAvailable instanceof Boolean && (Boolean) astAvailable;
            })
            .findFirst()
            .orElseThrow();
    Map<String, Object> metadata = target.getMetadata();
    assertThat(metadata.get("ast_available")).isEqualTo(true);
    assertThat(metadata.get("symbol_fqn")).as("symbol_fqn present").isNotNull();
    if ("java".equals(fixture.fixtureName())) {
      boolean hasDocstring =
          documents.stream()
              .map(Document::getMetadata)
              .filter(java.util.Objects::nonNull)
              .map(meta -> meta.get("docstring"))
              .anyMatch(value -> value instanceof String && !((String) value).isBlank());
      assertThat(hasDocstring).as("docstring present for java fixture").isTrue();
    }

    ArgumentCaptor<List<RepoRagSymbolGraphEntity>> edgesCaptor =
        ArgumentCaptor.forClass(List.class);
    verify(symbolGraphRepository, org.mockito.Mockito.atLeastOnce()).saveAll(edgesCaptor.capture());
    List<RepoRagSymbolGraphEntity> edges =
        edgesCaptor.getAllValues().stream().flatMap(List::stream).toList();
    assertThat(edges).isNotEmpty();
    if (fixture.expectedCall() != null) {
      assertThat(edges)
          .anySatisfy(
              edge -> assertThat(edge.getReferencedSymbolFqn())
                  .containsIgnoringCase(fixture.expectedCall()));
    }

  }

  private Path copyFixtureToWorkspace(String fixtureName) throws IOException {
    Path fixtureRoot =
        Path.of("src", "test", "resources", "mini-repos", fixtureName).toAbsolutePath();
    Path workspaceRoot = tempDir.resolve(fixtureName);
    Files.walkFileTree(
        fixtureRoot,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
              throws IOException {
            Path relative = fixtureRoot.relativize(dir);
            Path target = workspaceRoot.resolve(relative.toString());
            Files.createDirectories(target);
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
              throws IOException {
            Path relative = fixtureRoot.relativize(file);
            Path target = workspaceRoot.resolve(relative.toString());
            Files.createDirectories(target.getParent());
            Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
            return FileVisitResult.CONTINUE;
          }
        });
    return workspaceRoot;
  }

  private Workspace workspaceFor(Path root, MiniRepoFixture fixture) {
    Instant now = Instant.now();
    return new Workspace(
        fixture.workspaceId(),
        root,
        now,
        now.plusSeconds(600),
        "req-" + fixture.workspaceId(),
        fixture.repoOwner() + "/" + fixture.repoName(),
        "refs/heads/main",
        0L,
        null,
        List.of(),
        null,
        null,
        null);
  }

  private static Stream<MiniRepoFixture> fixtures() {
    return Stream.of(
        new MiniRepoFixture(
            "java", "owner", "java-demo", "src/main/java/com/example/DemoService.java", "log"),
        new MiniRepoFixture("kotlin", "owner", "kt-demo", "src/main/kotlin/com/example/DemoService.kt", null),
        new MiniRepoFixture(
            "typescript",
            "owner",
            "ts-demo",
            "src/app.ts",
            "helper"),
        new MiniRepoFixture(
            "javascript",
            "owner",
            "js-demo",
            "src/app.js",
            null),
        new MiniRepoFixture(
            "python", "owner", "py-demo", "src/demo_service.py", null),
        new MiniRepoFixture("go", "owner", "go-demo", "cmd/demo/main.go", null));
  }

  private record MiniRepoFixture(
      String fixtureName, String repoOwner, String repoName, String relativeFile, String expectedCall) {

    String workspaceId() {
      return "ws-" + fixtureName;
    }
  }
}
