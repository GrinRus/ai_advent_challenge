package com.aiadvent.mcp.backend.github.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.aiadvent.mcp.backend.config.GitHubRagProperties;
import com.aiadvent.mcp.backend.github.rag.persistence.RepoRagSymbolGraphEntity;
import com.aiadvent.mcp.backend.github.rag.persistence.RepoRagSymbolGraphRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class RepoRagSymbolServiceTest {

  @Mock private RepoRagSymbolGraphRepository repository;

  private RepoRagSymbolService service;

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);
    GitHubRagProperties properties = new GitHubRagProperties();
    properties.getGraph().setEnabled(false);
    service = new RepoRagSymbolService(repository, new SimpleMeterRegistry(), null, properties);
  }

  @Test
  void skipsLookupWhenInputBlank() {
    List<RepoRagSymbolService.SymbolNeighbor> neighbors = service.findCallGraphNeighbors("", "");
    assertThat(neighbors).isEmpty();
    verifyNoInteractions(repository);
  }

  @Test
  void returnsNeighborsForReferencedSymbol() {
    RepoRagSymbolGraphEntity entity = new RepoRagSymbolGraphEntity();
    entity.setNamespace("repo:demo");
    entity.setFilePath("src/App.java");
    entity.setChunkIndex(4);
    entity.setChunkHash("hash-4");
    entity.setRelation("CALLS");
    entity.setSymbolFqn("com.demo.Service#doWork");
    entity.setReferencedSymbolFqn("com.demo.Helper#run");

    when(repository.findByNamespaceAndReferencedSymbolFqn("repo:demo", "com.demo.Helper#run"))
        .thenReturn(List.of(entity));

    List<RepoRagSymbolService.SymbolNeighbor> neighbors =
        service.findCallGraphNeighbors("repo:demo", "com.demo.Helper#run");

    assertThat(neighbors).hasSize(1);
    RepoRagSymbolService.SymbolNeighbor neighbor = neighbors.getFirst();
    assertThat(neighbor.filePath()).isEqualTo("src/App.java");
    assertThat(neighbor.chunkIndex()).isEqualTo(4);
    assertThat(neighbor.chunkHash()).isEqualTo("hash-4");
    assertThat(neighbor.symbolFqn()).isEqualTo("com.demo.Service#doWork");
    assertThat(neighbor.referencedSymbolFqn()).isEqualTo("com.demo.Helper#run");
  }

  @Test
  void returnsOutgoingEdgesWithCaching() {
    RepoRagSymbolGraphEntity edge = new RepoRagSymbolGraphEntity();
    edge.setNamespace("repo:demo");
    edge.setFilePath("src/Helper.java");
    edge.setChunkIndex(2);
    edge.setChunkHash("hash-2");
    edge.setRelation("CALLS");
    edge.setSymbolFqn("com.demo.Helper#doWork");
    edge.setReferencedSymbolFqn("com.demo.Util#run");

    when(repository.findByNamespaceAndSymbolFqn("repo:demo", "com.demo.Helper#doWork"))
        .thenReturn(List.of(edge));

    List<RepoRagSymbolService.SymbolNeighbor> edges =
        service.findOutgoingEdges("repo:demo", "com.demo.Helper#doWork");

    assertThat(edges).hasSize(1);
    RepoRagSymbolService.SymbolNeighbor neighbor = edges.getFirst();
    assertThat(neighbor.filePath()).isEqualTo("src/Helper.java");
    assertThat(neighbor.referencedSymbolFqn()).isEqualTo("com.demo.Util#run");

    service.findOutgoingEdges("repo:demo", "com.demo.Helper#doWork");
    verify(repository, times(1)).findByNamespaceAndSymbolFqn("repo:demo", "com.demo.Helper#doWork");
  }
}
