package com.aiadvent.mcp.backend.github.rag;

import com.aiadvent.mcp.backend.github.rag.persistence.RepoRagSymbolGraphEntity;
import com.aiadvent.mcp.backend.github.rag.persistence.RepoRagSymbolGraphRepository;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class RepoRagSymbolService {

  private static final Logger log = LoggerFactory.getLogger(RepoRagSymbolService.class);

  private final RepoRagSymbolGraphRepository repository;

  public RepoRagSymbolService(RepoRagSymbolGraphRepository repository) {
    this.repository = repository;
  }

  public List<SymbolNeighbor> findCallGraphNeighbors(String namespace, String symbolFqn) {
    if (!hasText(namespace) || !hasText(symbolFqn)) {
      return List.of();
    }
    List<RepoRagSymbolGraphEntity> callers =
        repository.findByNamespaceAndReferencedSymbolFqn(
            namespace, normalizeSymbol(symbolFqn));
    if (callers.isEmpty()) {
      log.debug(
          "Call graph neighbor lookup has no results (namespace={}, symbol={})",
          namespace,
          symbolFqn);
      return List.of();
    }
    return callers.stream().map(this::toNeighbor).toList();
  }

  public Optional<SymbolDefinition> findSymbolDefinition(String namespace, String symbolFqn) {
    if (!hasText(namespace) || !hasText(symbolFqn)) {
      return Optional.empty();
    }
    return repository.findByNamespaceAndSymbolFqn(namespace, normalizeSymbol(symbolFqn)).stream()
        .findFirst()
        .map(entity -> new SymbolDefinition(
            entity.getFilePath(), entity.getChunkIndex(), entity.getChunkHash(), entity.getSymbolKind()));
  }

  private SymbolNeighbor toNeighbor(RepoRagSymbolGraphEntity entity) {
    return new SymbolNeighbor(
        entity.getFilePath(),
        entity.getChunkIndex(),
        entity.getChunkHash(),
        entity.getRelation(),
        entity.getSymbolFqn());
  }

  private boolean hasText(String value) {
    return StringUtils.hasText(value);
  }

  private String normalizeSymbol(String symbol) {
    return symbol.trim();
  }

  public record SymbolNeighbor(
      String filePath, int chunkIndex, String chunkHash, String relation, String symbolFqn) {}

  public record SymbolDefinition(
      String filePath, int chunkIndex, String chunkHash, String symbolKind) {}
}
