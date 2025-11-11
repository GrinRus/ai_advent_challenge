package com.aiadvent.mcp.backend.github.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aiadvent.mcp.backend.config.GitHubRagProperties;
import com.aiadvent.mcp.backend.github.rag.chunking.ChunkableFile;
import com.aiadvent.mcp.backend.github.rag.chunking.RepoRagChunker;
import com.aiadvent.mcp.backend.github.rag.persistence.RepoRagFileStateEntity;
import com.aiadvent.mcp.backend.github.rag.persistence.RepoRagFileStateRepository;
import com.aiadvent.mcp.backend.github.rag.persistence.RepoRagVectorStoreAdapter;
import com.aiadvent.mcp.backend.github.workspace.TempWorkspaceService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Arrays;
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
class RepoRagIndexServiceTest {

  private static final String NAMESPACE = "repo:owner/repo";

  @Mock private TempWorkspaceService workspaceService;
  @Mock private RepoRagVectorStoreAdapter vectorStoreAdapter;
  @Mock private RepoRagFileStateRepository fileStateRepository;

  @TempDir Path tempDir;

  private RepoRagIndexService service;
  private GitHubRagProperties properties;
  private RepoRagChunker chunker;

  @BeforeEach
  void setUp() {
    properties = new GitHubRagProperties();
    properties.getChunking().getLine().setMaxBytes(1024);
    properties.getChunking().getLine().setMaxLines(50);
    properties.getChunking().setOverlapLines(0);
    chunker = new RepoRagChunker(properties);
    service =
        new RepoRagIndexService(
            workspaceService, vectorStoreAdapter, fileStateRepository, chunker, properties);
  }

  @Test
  void indexesTextFilesAndSkipsBinaryOnes() throws IOException {
    Path srcDir = Files.createDirectory(tempDir.resolve("src"));
    Path textFile = srcDir.resolve("Main.java");
    Files.writeString(textFile, "public class Main {}\n");
    Path binaryFile = tempDir.resolve("logo.png");
    Files.write(binaryFile, new byte[] {0, 1, 2, 3});

    when(vectorStoreAdapter.listFilePaths(NAMESPACE)).thenReturn(mutableSet("ghost.js"));
    when(fileStateRepository.findByNamespace(NAMESPACE)).thenReturn(List.of());
    when(workspaceService.findWorkspace("ws-1"))
        .thenReturn(Optional.of(workspaceFor(tempDir)));

    RepoRagIndexService.IndexResult result =
        service.indexWorkspace(request("ws-1"));

    assertThat(result.filesProcessed()).isEqualTo(1);
    assertThat(result.chunksProcessed()).isEqualTo(1);
    assertThat(result.warnings()).anyMatch(w -> w.contains("binary file") && w.contains("logo.png"));

    ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
    verify(vectorStoreAdapter).replaceFile(eq(NAMESPACE), eq("src/Main.java"), captor.capture());
    verify(vectorStoreAdapter).deleteFile(NAMESPACE, "ghost.js");
    verify(fileStateRepository).save(any(RepoRagFileStateEntity.class));
    verify(fileStateRepository).deleteByNamespaceAndFilePath(NAMESPACE, "ghost.js");
    List<Document> documents = captor.getValue();
    assertThat(documents).hasSize(1);
    Document doc = documents.get(0);
    assertThat(doc.getMetadata().get("file_path")).isEqualTo("src/Main.java");
    assertThat(doc.getMetadata().get("repo_owner")).isEqualTo("owner");
    assertThat(doc.getMetadata().get("repo_name")).isEqualTo("repo");
    assertThat(doc.getMetadata().get("chunk_index")).isEqualTo(0);
  }

  @Test
  void splitsLargeFileIntoMultipleChunks() throws IOException {
    properties.getChunking().getLine().setMaxLines(3);
    Path file = tempDir.resolve("Large.java");
    String content = String.join("\n", List.of("line1", "line2", "line3", "line4", "line5", "line6"));
    Files.writeString(file, content);

    when(vectorStoreAdapter.listFilePaths(NAMESPACE)).thenReturn(mutableSet());
    when(fileStateRepository.findByNamespace(NAMESPACE)).thenReturn(List.of());
    when(workspaceService.findWorkspace("ws-2"))
        .thenReturn(Optional.of(workspaceFor(tempDir)));

    RepoRagIndexService.IndexResult result =
        service.indexWorkspace(request("ws-2"));

    assertThat(result.chunksProcessed()).isEqualTo(2);

    ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
    verify(vectorStoreAdapter).replaceFile(eq(NAMESPACE), eq("Large.java"), captor.capture());
    List<Document> documents = captor.getValue();
    assertThat(documents).hasSize(2);
    assertThat(documents.get(0).getMetadata().get("line_end")).isEqualTo(3);
    assertThat(documents.get(1).getMetadata().get("line_start")).isEqualTo(4);
  }

  @Test
  void honorsIgnoreDirectories() throws IOException {
    properties.getIgnore().setDirectories(List.of("node_modules", "dist"));

    Path ignoredDir = Files.createDirectory(tempDir.resolve("node_modules"));
    Files.writeString(ignoredDir.resolve("ignored.js"), "console.log('skip');");
    Path expectedDir = Files.createDirectories(tempDir.resolve("src"));
    Files.writeString(expectedDir.resolve("kept.ts"), "export const a = 1;");

    when(vectorStoreAdapter.listFilePaths(NAMESPACE)).thenReturn(mutableSet());
    when(fileStateRepository.findByNamespace(NAMESPACE)).thenReturn(List.of());
    when(workspaceService.findWorkspace("ws-3"))
        .thenReturn(Optional.of(workspaceFor(tempDir)));

    RepoRagIndexService.IndexResult result =
        service.indexWorkspace(request("ws-3"));

    assertThat(result.filesProcessed()).isEqualTo(1);

    ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
    verify(vectorStoreAdapter).replaceFile(eq(NAMESPACE), eq("src/kept.ts"), captor.capture());
    List<Document> documents = captor.getValue();
    assertThat(documents)
        .singleElement()
        .satisfies(doc -> assertThat(doc.getMetadata().get("file_path")).isEqualTo("src/kept.ts"));
  }

  @Test
  void skipsBrokenFilesWithoutDroppingExistingChunks() throws IOException {
    Path file = tempDir.resolve("broken.txt");
    Files.writeString(file, "secret");

    when(vectorStoreAdapter.listFilePaths(NAMESPACE)).thenReturn(mutableSet("broken.txt"));
    when(fileStateRepository.findByNamespace(NAMESPACE)).thenReturn(List.of());
    when(workspaceService.findWorkspace("ws-4"))
        .thenReturn(Optional.of(workspaceFor(tempDir)));

    RepoRagChunker failingChunker = mock(RepoRagChunker.class);
    when(failingChunker.chunk(any(ChunkableFile.class))).thenThrow(new RuntimeException("boom"));
    RepoRagIndexService flakyService =
        new RepoRagIndexService(
            workspaceService, vectorStoreAdapter, fileStateRepository, failingChunker, properties);

    RepoRagIndexService.IndexResult result =
        flakyService.indexWorkspace(request("ws-4"));

    assertThat(result.filesProcessed()).isZero();
    assertThat(result.warnings()).anyMatch(w -> w.contains("chunking failed"));

    verify(vectorStoreAdapter, never()).replaceFile(eq(NAMESPACE), eq("broken.txt"), any());
    verify(vectorStoreAdapter, never()).deleteFile(NAMESPACE, "broken.txt");
    verify(fileStateRepository, never()).deleteByNamespaceAndFilePath(NAMESPACE, "broken.txt");
  }

  @Test
  void skipsFilesWithUnchangedHash() throws Exception {
    Path file = tempDir.resolve("Same.java");
    Files.writeString(file, "class Sample {}\n");
    RepoRagFileStateEntity existing = new RepoRagFileStateEntity();
    existing.setNamespace(NAMESPACE);
    existing.setFilePath("Same.java");
    existing.setFileHash(hashFile(file));
    existing.setChunkCount(1);

    when(vectorStoreAdapter.listFilePaths(NAMESPACE)).thenReturn(mutableSet("Same.java"));
    when(fileStateRepository.findByNamespace(NAMESPACE)).thenReturn(List.of(existing));
    when(workspaceService.findWorkspace("ws-5"))
        .thenReturn(Optional.of(workspaceFor(tempDir)));

    RepoRagIndexService.IndexResult result = service.indexWorkspace(request("ws-5"));

    assertThat(result.filesProcessed()).isZero();
    assertThat(result.filesSkipped()).isEqualTo(1);

    verify(vectorStoreAdapter, never()).replaceFile(eq(NAMESPACE), eq("Same.java"), any());
    verify(fileStateRepository, never()).save(any());
  }

  @Test
  void storesSpanHashAndOverlapMetadata() throws IOException {
    properties.getChunking().getLine().setMaxLines(2);
    properties.getChunking().setOverlapLines(1);
    Path file = tempDir.resolve("Sample.java");
    Files.writeString(file, "line1\nline2\nline3\n");

    when(vectorStoreAdapter.listFilePaths(NAMESPACE)).thenReturn(mutableSet());
    when(fileStateRepository.findByNamespace(NAMESPACE)).thenReturn(List.of());
    when(workspaceService.findWorkspace("ws-span"))
        .thenReturn(Optional.of(workspaceFor(tempDir)));

    service.indexWorkspace(request("ws-span"));

    ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
    verify(vectorStoreAdapter).replaceFile(eq(NAMESPACE), eq("Sample.java"), captor.capture());
    List<Document> documents = captor.getValue();
    assertThat(documents).hasSizeGreaterThan(1);
    Document last = documents.get(documents.size() - 1);
    assertThat(last.getMetadata()).containsKeys("span_hash", "overlap_lines");
    assertThat(last.getMetadata().get("overlap_lines")).isEqualTo(1);
    assertThat(last.getMetadata().get("span_hash")).isInstanceOf(String.class);
  }

  private HashSet<String> mutableSet(String... values) {
    return new HashSet<>(Arrays.asList(values));
  }

  private TempWorkspaceService.Workspace workspaceFor(Path root) {
    return new TempWorkspaceService.Workspace(
        "ws",
        root,
        Instant.now(),
        Instant.now().plusSeconds(60),
        null,
        "owner/repo",
        "refs/heads/main",
        0L,
        null,
        List.of());
  }

  private RepoRagIndexService.IndexRequest request(String workspaceId) {
    return new RepoRagIndexService.IndexRequest(
        "owner",
        "repo",
        workspaceId,
        NAMESPACE,
        "refs/heads/main",
        "commit-sha",
        1024L,
        Instant.now());
  }

  private String hashFile(Path file) throws NoSuchAlgorithmException, IOException {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    try (var input = Files.newInputStream(file)) {
      byte[] buffer = new byte[8192];
      int read;
      while ((read = input.read(buffer)) != -1) {
        digest.update(buffer, 0, read);
      }
    }
    byte[] hash = digest.digest();
    StringBuilder builder = new StringBuilder(hash.length * 2);
    for (byte b : hash) {
      builder.append(String.format("%02x", b));
    }
    return builder.toString();
  }
}
