package com.aiadvent.mcp.backend.github.rag.postprocessing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.aiadvent.mcp.backend.github.rag.RepoRagSymbolService;
import com.aiadvent.mcp.backend.github.rag.RepoRagSymbolService.SymbolNeighbor;
import com.aiadvent.mcp.backend.github.rag.persistence.RepoRagDocumentEntity;
import com.aiadvent.mcp.backend.github.rag.persistence.RepoRagDocumentMapper;
import com.aiadvent.mcp.backend.github.rag.persistence.RepoRagDocumentRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.document.Document;

class NeighborChunkDocumentPostProcessorTest {

  private RepoRagDocumentRepository documentRepository;
  private RepoRagDocumentMapper documentMapper;
  private RepoRagSymbolService symbolService;

  @BeforeEach
  void setUp() {
    documentRepository = mock(RepoRagDocumentRepository.class);
    documentMapper = Mockito.spy(new RepoRagDocumentMapper(new ObjectMapper()));
    symbolService = mock(RepoRagSymbolService.class);
  }

  @Test
  void addsLinearNeighborsWithinRadiusAndLimit() {
    Document anchor =
        buildDocument("repo:demo", "src/App.java", 10, "hash-10", "span-10", 0.92);
    RepoRagDocumentEntity left = entity("repo:demo", "src/App.java", 9, "hash-9");
    RepoRagDocumentEntity right = entity("repo:demo", "src/App.java", 11, "hash-11");
    when(documentRepository.findByNamespaceAndFilePathAndChunkIndexIn(
            Mockito.eq("repo:demo"), Mockito.eq("src/App.java"), Mockito.anyCollection()))
        .thenReturn(List.of(left, right));

    NeighborChunkDocumentPostProcessor processor =
        new NeighborChunkDocumentPostProcessor(
            documentRepository,
            documentMapper,
            symbolService,
            RepoRagPostProcessingRequest.NeighborStrategy.LINEAR,
            1,
            2,
            true);

    List<Document> expanded = processor.process(null, List.of(anchor));

    assertThat(expanded).hasSize(3);
    Document neighbor = expanded.get(1);
    assertThat(neighbor.getMetadata().get("neighborOfSpanHash")).isEqualTo("span-10");
    assertThat(neighbor.getMetadata().get("chunk_hash")).isEqualTo("hash-9");
  }

  @Test
  void skipsNeighborsWhenAlreadyPresent() {
    Document anchor =
        buildDocument("repo:demo", "src/App.java", 5, "hash-5", "span-5", 0.5);
    RepoRagDocumentEntity duplicate = entity("repo:demo", "src/App.java", 4, "hash-5");
    when(documentRepository.findByNamespaceAndFilePathAndChunkIndexIn(
            Mockito.eq("repo:demo"), Mockito.eq("src/App.java"), Mockito.anyCollection()))
        .thenReturn(List.of(duplicate));

    NeighborChunkDocumentPostProcessor processor =
        new NeighborChunkDocumentPostProcessor(
            documentRepository,
            documentMapper,
            symbolService,
            RepoRagPostProcessingRequest.NeighborStrategy.LINEAR,
            1,
            2,
            true);

    List<Document> expanded = processor.process(null, List.of(anchor));
    assertThat(expanded).containsExactly(anchor);
  }

  @Test
  void usesCallGraphNeighborsFromService() {
    Document anchor = buildDocumentWithExtra(
        "repo:demo",
        "src/App.java",
        5,
        "hash-5",
        "span-5",
        0.5,
        Map.of("symbol_fqn", "class Demo"));
    RepoRagDocumentEntity referenced = entity("repo:demo", "src/Service.java", 2, "hash-6");
    when(symbolService.findCallGraphNeighbors("repo:demo", "class Demo"))
        .thenReturn(
            List.of(
                new SymbolNeighbor(
                    "src/Service.java",
                    2,
                    "hash-6",
                    "CALLS",
                    "com.demo.Service#doWork",
                    "com.demo.Helper#doWork")));
    when(documentRepository.findByNamespaceAndChunkHashIn("repo:demo", List.of("hash-6")))
        .thenReturn(List.of(referenced));

    NeighborChunkDocumentPostProcessor processor =
        new NeighborChunkDocumentPostProcessor(
            documentRepository,
            documentMapper,
            symbolService,
            RepoRagPostProcessingRequest.NeighborStrategy.CALL_GRAPH,
            0,
            2,
            true);

    List<Document> expanded = processor.process(null, List.of(anchor));
    assertThat(expanded).hasSize(2);
    assertThat(expanded.get(1).getMetadata().get("neighborOfSpanHash")).isEqualTo("span-5");
    assertThat(expanded.get(1).getMetadata().get("neighbor_relation")).isEqualTo("CALLS");
    assertThat(expanded.get(1).getMetadata().get("neighbor_symbol"))
        .isEqualTo("com.demo.Service#doWork");
    assertThat(expanded.get(1).getMetadata().get("neighbor_referenced_symbol"))
        .isEqualTo("com.demo.Helper#doWork");
  }

  @Test
  void callGraphNeighborsDisabledWhenAstNotReady() {
    Document anchor =
        buildDocumentWithExtra(
            "repo:demo",
            "src/App.java",
            5,
            "hash-5",
            "span-5",
            0.8,
            Map.of("symbol_fqn", "class Demo"));

    NeighborChunkDocumentPostProcessor processor =
        new NeighborChunkDocumentPostProcessor(
            documentRepository,
            documentMapper,
            symbolService,
            RepoRagPostProcessingRequest.NeighborStrategy.CALL_GRAPH,
            0,
            2,
            false);

    List<Document> expanded = processor.process(null, List.of(anchor));
    assertThat(expanded).containsExactly(anchor);
    verifyNoInteractions(symbolService);
  }

  private Document buildDocument(
      String namespace,
      String filePath,
      int chunkIndex,
      String chunkHash,
      String spanHash,
      double score) {
    return buildDocumentWithExtra(
        namespace, filePath, chunkIndex, chunkHash, spanHash, score, Map.of());
  }

  private Document buildDocumentWithExtra(
      String namespace,
      String filePath,
      int chunkIndex,
      String chunkHash,
      String spanHash,
      double score,
      Map<String, Object> extra) {
    java.util.Map<String, Object> metadata =
        new java.util.LinkedHashMap<>(
            Map.of(
                "namespace", namespace,
                "file_path", filePath,
                "chunk_index", chunkIndex,
                "chunk_hash", chunkHash,
                "span_hash", spanHash));
    metadata.putAll(extra);
    return Document.builder()
        .id(chunkHash)
        .text("snippet")
        .metadata(metadata)
        .score(score)
        .build();
  }

  private RepoRagDocumentEntity entity(String namespace, String path, int chunkIndex, String hash) {
    RepoRagDocumentEntity entity = new RepoRagDocumentEntity();
    try {
      java.lang.reflect.Field idField = RepoRagDocumentEntity.class.getDeclaredField("id");
      idField.setAccessible(true);
      idField.set(entity, java.util.UUID.randomUUID());
    } catch (NoSuchFieldException | IllegalAccessException ignore) {
      // ignore
    }
    entity.setNamespace(namespace);
    entity.setFilePath(path);
    entity.setChunkIndex(chunkIndex);
    entity.setChunkHash(hash);
    entity.setContent("// code");
    return entity;
  }
}
