package com.aiadvent.mcp.backend.github.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aiadvent.mcp.backend.config.GitHubRagProperties;
import com.aiadvent.mcp.backend.github.rag.persistence.RepoRagVectorStoreAdapter;
import com.aiadvent.mcp.backend.github.workspace.TempWorkspaceService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
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
class RepoRagIndexServiceTest {

  @Mock private TempWorkspaceService workspaceService;
  @Mock private RepoRagVectorStoreAdapter vectorStoreAdapter;

  @TempDir Path tempDir;

  private RepoRagIndexService service;

  @BeforeEach
  void setUp() {
    GitHubRagProperties properties = new GitHubRagProperties();
    properties.getChunk().setMaxBytes(1024);
    properties.getChunk().setMaxLines(50);
    service = new RepoRagIndexService(workspaceService, vectorStoreAdapter, properties);
  }

  @Test
  void indexesTextFilesAndSkipsBinaryOnes() throws IOException {
    Path srcDir = Files.createDirectory(tempDir.resolve("src"));
    Path textFile = srcDir.resolve("Main.java");
    Files.writeString(textFile, "public class Main {}\n");
    Path binaryFile = tempDir.resolve("logo.png");
    Files.write(binaryFile, new byte[] {0, 1, 2, 3});

    TempWorkspaceService.Workspace workspace =
        new TempWorkspaceService.Workspace(
            "ws-1",
            tempDir,
            Instant.now(),
            Instant.now().plusSeconds(60),
            null,
            "owner/repo",
            "refs/heads/main",
            0L,
            null,
            List.of());
    when(workspaceService.findWorkspace("ws-1")).thenReturn(Optional.of(workspace));

    RepoRagIndexService.IndexResult result =
        service.indexWorkspace(
            new RepoRagIndexService.IndexRequest(
                "owner", "repo", "ws-1", "repo:owner/repo", "refs/heads/main", Instant.now()));

    assertThat(result.filesProcessed()).isEqualTo(1);
    assertThat(result.chunksProcessed()).isEqualTo(1);
    assertThat(result.warnings()).anyMatch(w -> w.contains("binary file") && w.contains("logo.png"));

    ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
    verify(vectorStoreAdapter).replaceNamespace(eq("repo:owner/repo"), captor.capture());
    List<Document> documents = captor.getValue();
    assertThat(documents).hasSize(1);
    Document doc = documents.get(0);
    assertThat(doc.getMetadata().get("file_path")).isEqualTo("src/Main.java");
    assertThat(doc.getMetadata().get("repo_owner")).isEqualTo("owner");
    assertThat(doc.getMetadata().get("repo_name")).isEqualTo("repo");
    assertThat(doc.getMetadata().get("chunk_index")).isEqualTo(0);
  }
}
