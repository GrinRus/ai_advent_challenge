package com.aiadvent.mcp.backend.github.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aiadvent.mcp.backend.config.GitHubRagProperties;
import com.aiadvent.mcp.backend.github.rag.ast.AstFileContextFactory;
import com.aiadvent.mcp.backend.github.rag.ast.TreeSitterAnalyzer;
import com.aiadvent.mcp.backend.github.rag.ast.TreeSitterParser;
import com.aiadvent.mcp.backend.github.rag.chunking.RepoRagChunker;
import com.aiadvent.mcp.backend.github.rag.persistence.RepoRagFileStateRepository;
import com.aiadvent.mcp.backend.github.rag.persistence.RepoRagSymbolGraphEntity;
import com.aiadvent.mcp.backend.github.rag.persistence.RepoRagSymbolGraphRepository;
import com.aiadvent.mcp.backend.github.rag.persistence.RepoRagVectorStoreAdapter;
import com.aiadvent.mcp.backend.github.workspace.TempWorkspaceService;
import com.aiadvent.mcp.backend.github.workspace.TempWorkspaceService.Workspace;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;

@ExtendWith(MockitoExtension.class)
class RepoRagIndexServiceAstIntegrationTest {

  private static final String NAMESPACE = "repo:owner/sample";

  @Mock private TempWorkspaceService workspaceService;
  @Mock private RepoRagVectorStoreAdapter vectorStoreAdapter;
  @Mock private RepoRagFileStateRepository fileStateRepository;
  @Mock private RepoRagSymbolGraphRepository symbolGraphRepository;

  @TempDir Path tempDir;

  private RepoRagIndexService service;
  private AstFileContextFactory astFactory;

  @BeforeEach
  void setUp() {
    GitHubRagProperties properties = new GitHubRagProperties();
    properties.getChunking().getLine().setMaxLines(4);
    properties.getChunking().setOverlapLines(0);
    properties.getAst().setEnabled(true);
    properties.getAst().setLanguages(List.of("java"));

    RepoRagChunker chunker = new RepoRagChunker(properties);
    TreeSitterAnalyzer analyzer = mock(TreeSitterAnalyzer.class);
    when(analyzer.isEnabled()).thenReturn(true);
    when(analyzer.supportsLanguage(anyString())).thenReturn(true);
    when(analyzer.ensureLanguageLoaded(anyString())).thenReturn(true);

    astFactory = new AstFileContextFactory(analyzer, new TreeSitterParser());
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
            null);
  }

  @Test
  void indexesJavaMiniRepoWithAstMetadata() throws IOException {
    Path sourceDir = Files.createDirectories(tempDir.resolve("src/main/java/com/example"));
    Path javaFile = sourceDir.resolve("DemoService.java");
    String content =
        "package com.example;\n" +
            "import java.util.List;\n" +
            "\n" +
            "/** Demo service */\n" +
            "public class DemoService {\n" +
            "  public void process() {\n" +
            "    helper();\n" +
            "  }\n" +
            "}\n";
    Files.writeString(javaFile, content);

    assertThat(
            astFactory.create(
                javaFile,
                "src/main/java/com/example/DemoService.java",
                "java",
                content))
        .isNotNull();

    when(workspaceService.findWorkspace("ws-ast"))
        .thenReturn(Optional.of(workspaceFor(tempDir)));
    when(vectorStoreAdapter.listFilePaths(NAMESPACE)).thenReturn(new HashSet<>());
    when(fileStateRepository.findByNamespace(NAMESPACE)).thenReturn(List.of());

    RepoRagIndexService.IndexResult result =
        service.indexWorkspace(
            new RepoRagIndexService.IndexRequest(
                "owner",
                "sample",
                "ws-ast",
                NAMESPACE,
                "refs/heads/main",
                null,
                0L,
                Instant.now()));

    assertThat(result.astReady()).isTrue();

    ArgumentCaptor<List<Document>> docCaptor = ArgumentCaptor.forClass(List.class);
    verify(vectorStoreAdapter)
        .replaceFile(
            eq(NAMESPACE),
            eq("src/main/java/com/example/DemoService.java"),
            docCaptor.capture());
    List<Document> documents = docCaptor.getValue();
    assertThat(documents).hasSize(3);
    Document document = documents.get(2);
    assertThat(document.getMetadata().get("ast_available")).isEqualTo(true);
    assertThat(document.getMetadata().get("symbol_fqn")).isNotNull();
    verify(symbolGraphRepository).saveAll(anyList());
  }

  private Workspace workspaceFor(Path root) {
    Instant now = Instant.now();
    return new Workspace(
        "ws-ast",
        root,
        now,
        now.plusSeconds(3600),
        "req-1",
        "owner/sample",
        "refs/heads/main",
        0L,
        null,
        List.of(),
        null,
        null,
        null);
  }
}
