package com.aiadvent.mcp.backend.github.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.aiadvent.mcp.backend.github.rag.chunking.AstSymbolMetadata;
import com.aiadvent.mcp.backend.github.rag.chunking.Chunk;
import com.aiadvent.mcp.backend.github.rag.persistence.RepoRagSymbolGraphEntity;
import com.aiadvent.mcp.backend.github.rag.persistence.RepoRagSymbolGraphRepository;
import java.nio.charset.StandardCharsets;
import java.util.List;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class SymbolGraphWriterTest {

  @Mock private RepoRagSymbolGraphRepository repository;

  private SymbolGraphWriter writer;

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);
    writer = new SymbolGraphWriter(repository, new SimpleMeterRegistry());
  }

  @Test
  void syncFileSavesEdges() {
    AstSymbolMetadata ast =
        new AstSymbolMetadata(
            "com.demo.Service#doWork",
            "method",
            "public",
            "void doWork()",
            "Doc",
            false,
            List.of("java.util.List"),
            List.of("com.demo.Helper#run", "com.demo.Helper#run"),
            List.<String>of(),
            1,
            5);
    Chunk chunk =
        new Chunk(
            "text",
            1,
            5,
            "java",
            "summary",
            sha256("text"),
            null,
            0,
            ast);

    writer.syncFile("ns", "Service.java", List.of(chunk));

    verify(repository).deleteByNamespaceAndFilePath("ns", "Service.java");
    ArgumentCaptor<List<RepoRagSymbolGraphEntity>> captor = ArgumentCaptor.forClass(List.class);
    verify(repository).saveAll(captor.capture());
    List<RepoRagSymbolGraphEntity> entities = captor.getValue();
    assertThat(entities)
        .singleElement()
        .extracting(RepoRagSymbolGraphEntity::getReferencedSymbolFqn, RepoRagSymbolGraphEntity::getRelation)
        .containsExactly("com.demo.Helper#run", "CALLS");
  }

  @Test
  void syncFileSkipsWhenNoSymbols() {
    writer.syncFile("ns", "Empty.java", List.of());

    verify(repository).deleteByNamespaceAndFilePath("ns", "Empty.java");
    verify(repository, org.mockito.Mockito.never()).saveAll(org.mockito.Mockito.anyList());
  }

  @Test
  void deleteFileRemovesEdges() {
    writer.deleteFile("ns", "Orphan.java");

    verify(repository).deleteByNamespaceAndFilePath("ns", "Orphan.java");
  }

  private static String sha256(String value) {
    return com.aiadvent.mcp.backend.github.rag.chunking.Chunk.from(
            value, 1, 1, "", null, 0, null)
        .hash();
  }
}
