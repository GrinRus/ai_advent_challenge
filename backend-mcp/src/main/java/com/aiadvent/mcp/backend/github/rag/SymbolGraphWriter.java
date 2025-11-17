package com.aiadvent.mcp.backend.github.rag;

import com.aiadvent.mcp.backend.github.rag.chunking.AstSymbolMetadata;
import com.aiadvent.mcp.backend.github.rag.chunking.Chunk;
import com.aiadvent.mcp.backend.github.rag.persistence.RepoRagSymbolGraphEntity;
import com.aiadvent.mcp.backend.github.rag.persistence.RepoRagSymbolGraphRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

@Component
public class SymbolGraphWriter {

  private static final Logger log = LoggerFactory.getLogger(SymbolGraphWriter.class);

  private final RepoRagSymbolGraphRepository repository;
  private final Counter edgesWrittenCounter;
  private final Counter writerInvocations;

  public SymbolGraphWriter(RepoRagSymbolGraphRepository repository, MeterRegistry meterRegistry) {
    this.repository = repository;
    this.edgesWrittenCounter = meterRegistry.counter("github_rag_symbol_edges_written_total");
    this.writerInvocations = meterRegistry.counter("github_rag_symbol_writer_invocations_total");
  }

  public void syncFile(String namespace, String filePath, List<Chunk> chunks) {
    writerInvocations.increment();
    repository.deleteByNamespaceAndFilePath(namespace, filePath);
    if (CollectionUtils.isEmpty(chunks)) {
      return;
    }
    List<RepoRagSymbolGraphEntity> batch = new ArrayList<>();
    for (int index = 0; index < chunks.size(); index++) {
      Chunk chunk = chunks.get(index);
      AstSymbolMetadata ast = chunk.astMetadata();
      if (ast == null || !StringUtils.hasText(ast.symbolFqn())) {
        continue;
      }
      appendEdges(batch, namespace, filePath, index, chunk.hash(), ast, ast.callsOut(), "CALLS");
      appendEdges(batch, namespace, filePath, index, chunk.hash(), ast, ast.callsIn(), "CALLED_BY");
    }
    if (batch.isEmpty()) {
      return;
    }
    repository.saveAll(batch);
    edgesWrittenCounter.increment(batch.size());
    log.info(
        "Symbol graph synced (namespace={}, file={}, edges={})",
        namespace,
        filePath,
        batch.size());
  }

  public void deleteFile(String namespace, String filePath) {
    repository.deleteByNamespaceAndFilePath(namespace, filePath);
    log.debug("Symbol graph edges removed (namespace={}, file={})", namespace, filePath);
  }

  private void appendEdges(
      List<RepoRagSymbolGraphEntity> target,
      String namespace,
      String filePath,
      int chunkIndex,
      String chunkHash,
      AstSymbolMetadata ast,
      List<String> references,
      String relation) {
    if (CollectionUtils.isEmpty(references)) {
      return;
    }
    Set<String> unique = new LinkedHashSet<>();
    for (String reference : references) {
      if (StringUtils.hasText(reference)) {
        unique.add(reference.trim());
      }
    }
    for (String reference : unique) {
      RepoRagSymbolGraphEntity entity = new RepoRagSymbolGraphEntity();
      entity.setNamespace(namespace);
      entity.setFilePath(filePath);
      entity.setChunkIndex(chunkIndex);
      entity.setChunkHash(chunkHash);
      entity.setSymbolFqn(ast.symbolFqn());
      entity.setSymbolKind(ast.symbolKind());
      entity.setReferencedSymbolFqn(reference);
      entity.setRelation(relation);
      target.add(entity);
    }
  }
}
