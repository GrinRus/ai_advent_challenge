package com.aiadvent.mcp.backend.github.rag.postprocessing;

import com.aiadvent.mcp.backend.github.rag.RepoRagSymbolService;
import com.aiadvent.mcp.backend.github.rag.RepoRagSymbolService.SymbolNeighbor;
import com.aiadvent.mcp.backend.github.rag.persistence.RepoRagDocumentEntity;
import com.aiadvent.mcp.backend.github.rag.persistence.RepoRagDocumentMapper;
import com.aiadvent.mcp.backend.github.rag.persistence.RepoRagDocumentRepository;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.postretrieval.document.DocumentPostProcessor;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

/**
 * Expands the document list with neighbor chunks without hitting the vector store again.
 */
public class NeighborChunkDocumentPostProcessor implements DocumentPostProcessor {

  private static final Logger log =
      LoggerFactory.getLogger(NeighborChunkDocumentPostProcessor.class);

  private final RepoRagDocumentRepository documentRepository;
  private final RepoRagDocumentMapper documentMapper;
  private final RepoRagSymbolService symbolService;
  private final RepoRagPostProcessingRequest.NeighborStrategy strategy;
  private final int neighborRadius;
  private final int neighborLimit;
  private final boolean callGraphAllowed;

  public NeighborChunkDocumentPostProcessor(
      RepoRagDocumentRepository documentRepository,
      RepoRagDocumentMapper documentMapper,
      RepoRagSymbolService symbolService,
      RepoRagPostProcessingRequest.NeighborStrategy strategy,
      int neighborRadius,
      int neighborLimit,
      boolean callGraphAllowed) {
    this.documentRepository = documentRepository;
    this.documentMapper = documentMapper;
    this.symbolService = symbolService;
    this.strategy =
        strategy != null ? strategy : RepoRagPostProcessingRequest.NeighborStrategy.OFF;
    this.neighborRadius = Math.max(0, neighborRadius);
    this.neighborLimit = Math.max(0, neighborLimit);
    this.callGraphAllowed = callGraphAllowed;
  }

  @Override
  public List<Document> process(Query query, List<Document> documents) {
    if (strategy == RepoRagPostProcessingRequest.NeighborStrategy.OFF
        || neighborLimit <= 0
        || CollectionUtils.isEmpty(documents)) {
      return documents;
    }
    Set<String> seenHashes = collectChunkHashes(documents);
    List<Document> result = new ArrayList<>();
    int inserted = 0;
    for (Document document : documents) {
      result.add(document);
      if (inserted >= neighborLimit) {
        continue;
      }
      List<Document> neighbors =
          findNeighbors(document, seenHashes, neighborLimit - inserted);
      if (neighbors.isEmpty()) {
        continue;
      }
      for (Document neighbor : neighbors) {
        result.add(neighbor);
        String hash = extractChunkHash(neighbor);
        if (StringUtils.hasText(hash)) {
          seenHashes.add(hash);
        }
        inserted++;
        if (inserted >= neighborLimit) {
          break;
        }
      }
    }
    return inserted > 0 ? List.copyOf(result) : documents;
  }

  private List<Document> findNeighbors(
      Document anchor, Set<String> seenHashes, int remainingBudget) {
    return switch (strategy) {
      case LINEAR -> expandLinear(anchor, seenHashes, remainingBudget);
      case PARENT_SYMBOL -> expandParentSymbol(anchor, seenHashes, remainingBudget);
      case CALL_GRAPH ->
          callGraphAllowed ? expandCallGraph(anchor, seenHashes, remainingBudget) : List.of();
      case OFF -> List.of();
    };
  }

  private List<Document> expandLinear(
      Document anchor, Set<String> seenHashes, int remainingBudget) {
    if (neighborRadius <= 0) {
      return List.of();
    }
    String namespace = extractNamespace(anchor);
    String filePath = extractFilePath(anchor);
    Integer chunkIndex = extractChunkIndex(anchor);
    if (!StringUtils.hasText(namespace) || !StringUtils.hasText(filePath) || chunkIndex == null) {
      return List.of();
    }
    List<Integer> orderedIndexes = new ArrayList<>();
    for (int offset = 1; offset <= neighborRadius; offset++) {
      if (chunkIndex - offset >= 0) {
        orderedIndexes.add(chunkIndex - offset);
      }
      orderedIndexes.add(chunkIndex + offset);
    }
    return loadChunkIndexNeighbors(
        namespace,
        filePath,
        orderedIndexes,
        seenHashes,
        remainingBudget,
        extractSpanHash(anchor),
        anchor.getScore());
  }

  private List<Document> expandParentSymbol(
      Document anchor, Set<String> seenHashes, int remainingBudget) {
    String namespace = extractNamespace(anchor);
    String filePath = extractFilePath(anchor);
    String candidateSymbol = extractSymbolFqn(anchor);
    if (!StringUtils.hasText(candidateSymbol)) {
      candidateSymbol = extractParentSymbol(anchor);
    }
    if (!StringUtils.hasText(namespace)
        || !StringUtils.hasText(filePath)
        || !StringUtils.hasText(candidateSymbol)) {
      return List.of();
    }
    final String symbol = candidateSymbol;
    List<RepoRagDocumentEntity> entities =
        documentRepository.findByNamespaceAndFilePath(namespace, filePath);
    if (CollectionUtils.isEmpty(entities)) {
      log.debug(
          "No parent-symbol neighbors found (namespace={}, file={})",
          namespace,
          filePath);
      return List.of();
    }
    List<RepoRagDocumentEntity> filtered =
        entities.stream()
            .filter(entity -> symbolMatches(entity, symbol))
            .sorted(Comparator.comparingInt(RepoRagDocumentEntity::getChunkIndex))
            .toList();
    return convertAndFilterNeighbors(
        filtered,
        seenHashes,
        remainingBudget,
        extractSpanHash(anchor),
        anchor.getScore());
  }

  private boolean symbolMatches(RepoRagDocumentEntity entity, String target) {
    JsonNode metadata = entity.getMetadata();
    if (metadata == null || metadata.isNull()) {
      return false;
    }
    String symbolFqn = asLowerCase(metadata.get("symbol_fqn"));
    if (StringUtils.hasText(symbolFqn)) {
      return target.equalsIgnoreCase(symbolFqn);
    }
    String parentSymbol = asLowerCase(metadata.get("parent_symbol"));
    return StringUtils.hasText(parentSymbol) && target.equalsIgnoreCase(parentSymbol);
  }

  private List<Document> expandCallGraph(
      Document anchor, Set<String> seenHashes, int remainingBudget) {
    String namespace = extractNamespace(anchor);
    String symbol = extractSymbolFqn(anchor);
    if (!StringUtils.hasText(namespace) || !StringUtils.hasText(symbol)) {
      return List.of();
    }
    if (!callGraphAllowed) {
      log.debug(
          "Call graph neighbors disabled for namespace={} (strategy requested but AST not ready)",
          namespace);
      return List.of();
    }
    List<SymbolNeighbor> references =
        symbolService.findCallGraphNeighbors(namespace, symbol);
    if (CollectionUtils.isEmpty(references)) {
      log.debug(
          "Call graph neighbors not available for namespace={}, symbol={}",
          namespace,
          symbol);
      return List.of();
    }
    return loadChunkHashNeighbors(
        namespace,
        references,
        seenHashes,
        remainingBudget,
        extractSpanHash(anchor),
        anchor.getScore());
  }

  private List<Document> loadChunkHashNeighbors(
      String namespace,
      List<SymbolNeighbor> orderedNeighbors,
      Set<String> seenHashes,
      int remainingBudget,
      String anchorSpanHash,
      Double anchorScore) {
    if (CollectionUtils.isEmpty(orderedNeighbors)) {
      return List.of();
    }
    List<SymbolNeighbor> filtered =
        orderedNeighbors.stream()
            .filter(neighbor -> StringUtils.hasText(neighbor.chunkHash()))
            .toList();
    if (filtered.isEmpty()) {
      return List.of();
    }
    List<String> orderedHashes =
        filtered.stream().map(SymbolNeighbor::chunkHash).distinct().toList();
    List<RepoRagDocumentEntity> entities =
        documentRepository.findByNamespaceAndChunkHashIn(namespace, orderedHashes);
    if (CollectionUtils.isEmpty(entities)) {
      log.debug(
          "No call-graph neighbors fetched for namespace={}, hashes={}", namespace, orderedHashes);
      return List.of();
    }
    Map<String, RepoRagDocumentEntity> byHash =
        entities.stream()
            .collect(Collectors.toMap(RepoRagDocumentEntity::getChunkHash, entity -> entity));
    Map<String, SymbolNeighbor> neighborByHash =
        filtered.stream()
            .collect(Collectors.toMap(SymbolNeighbor::chunkHash, n -> n, (a, b) -> a));
    List<Document> ordered = new ArrayList<>();
    for (String hash : orderedHashes) {
      RepoRagDocumentEntity entity = byHash.get(hash);
      if (entity == null) {
        continue;
      }
      Document mapped = documentMapper.toDocument(entity);
      Document enriched =
          enrichNeighborDocument(
              mapped,
              anchorSpanHash,
              anchorScore,
              neighborByHash.get(hash).relation(),
              neighborByHash.get(hash).symbolFqn(),
              neighborByHash.get(hash).referencedSymbolFqn());
      String chunkHash = extractChunkHash(enriched);
      if (!StringUtils.hasText(chunkHash) || seenHashes.contains(chunkHash)) {
        continue;
      }
      ordered.add(enriched);
      if (ordered.size() >= remainingBudget) {
        break;
      }
    }
    return ordered;
  }

  private List<Document> loadChunkIndexNeighbors(
      String namespace,
      String filePath,
      List<Integer> orderedIndexes,
      Set<String> seenHashes,
      int remainingBudget,
      String anchorSpanHash,
      Double anchorScore) {
    if (CollectionUtils.isEmpty(orderedIndexes)) {
      return List.of();
    }
    List<Integer> uniqueIndexes =
        orderedIndexes.stream().distinct().collect(Collectors.toList());
    List<RepoRagDocumentEntity> entities =
        documentRepository.findByNamespaceAndFilePathAndChunkIndexIn(
            namespace, filePath, uniqueIndexes);
    if (CollectionUtils.isEmpty(entities)) {
      log.debug(
          "No linear neighbors fetched for namespace={}, file={}, indexes={}",
          namespace,
          filePath,
          uniqueIndexes);
      return List.of();
    }
    Map<Integer, RepoRagDocumentEntity> byIndex =
        entities.stream()
            .collect(Collectors.toMap(
                RepoRagDocumentEntity::getChunkIndex, entity -> entity, (a, b) -> a));
    List<Document> ordered = new ArrayList<>();
    for (Integer index : orderedIndexes) {
      RepoRagDocumentEntity entity = byIndex.get(index);
      if (entity == null) {
        continue;
      }
      Document mapped = documentMapper.toDocument(entity);
      Document enriched =
          enrichNeighborDocument(mapped, anchorSpanHash, anchorScore, null, null, null);
      String chunkHash = extractChunkHash(enriched);
      if (!StringUtils.hasText(chunkHash) || seenHashes.contains(chunkHash)) {
        continue;
      }
      ordered.add(enriched);
      if (ordered.size() >= remainingBudget) {
        break;
      }
    }
    return ordered;
  }

  private List<Document> convertAndFilterNeighbors(
      List<RepoRagDocumentEntity> entities,
      Set<String> seenHashes,
      int remainingBudget,
      String anchorSpanHash,
      Double anchorScore) {
    List<Document> result = new LinkedList<>();
    for (RepoRagDocumentEntity entity : entities) {
      Document mapped = documentMapper.toDocument(entity);
      String chunkHash = extractChunkHash(mapped);
      if (!StringUtils.hasText(chunkHash) || seenHashes.contains(chunkHash)) {
        continue;
      }
      Document enriched =
          enrichNeighborDocument(mapped, anchorSpanHash, anchorScore, null, null, null);
      result.add(enriched);
      if (result.size() >= remainingBudget) {
        break;
      }
    }
    return result;
  }

  private Document enrichNeighborDocument(
      Document neighbor,
      String anchorSpanHash,
      Double anchorScore,
      String relation,
      String neighborSymbol,
      String referencedSymbol) {
    Map<String, Object> metadata =
        neighbor.getMetadata() != null ? new LinkedHashMap<>(neighbor.getMetadata()) : new LinkedHashMap<>();
    if (StringUtils.hasText(anchorSpanHash)) {
      metadata.put("neighborOfSpanHash", anchorSpanHash);
    }
    if (StringUtils.hasText(relation)) {
      metadata.put("neighbor_relation", relation);
    }
    if (StringUtils.hasText(neighborSymbol)) {
      metadata.put("neighbor_symbol", neighborSymbol);
    }
    if (StringUtils.hasText(referencedSymbol)) {
      metadata.put("neighbor_referenced_symbol", referencedSymbol);
    }
    return Document.builder()
        .id(neighbor.getId())
        .text(neighbor.getText())
        .metadata(metadata)
        .score(anchorScore)
        .build();
  }

  private Set<String> collectChunkHashes(List<Document> documents) {
    return documents.stream()
        .map(this::extractChunkHash)
        .filter(StringUtils::hasText)
        .collect(Collectors.toSet());
  }

  private String extractNamespace(Document document) {
    return metadataString(document, "namespace");
  }

  private String extractFilePath(Document document) {
    return metadataString(document, "file_path");
  }

  private String extractSpanHash(Document document) {
    return metadataString(document, "span_hash");
  }

  private String extractSymbolFqn(Document document) {
    String symbol = metadataString(document, "symbol_fqn");
    if (StringUtils.hasText(symbol)) {
      return symbol;
    }
    return metadataString(document, "parent_symbol");
  }

  private String extractParentSymbol(Document document) {
    return metadataString(document, "parent_symbol");
  }

  private Integer extractChunkIndex(Document document) {
    Object value = metadataValue(document, "chunk_index");
    if (value instanceof Number number) {
      return number.intValue();
    }
    if (value instanceof String string && StringUtils.hasText(string)) {
      try {
        return Integer.parseInt(string.trim());
      } catch (NumberFormatException ignore) {
        // ignore
      }
    }
    return null;
  }

  private String extractChunkHash(Document document) {
    return metadataString(document, "chunk_hash");
  }

  private String metadataString(Document document, String key) {
    Object value = metadataValue(document, key);
    if (value instanceof String string && StringUtils.hasText(string)) {
      return string;
    }
    return null;
  }

  private Object metadataValue(Document document, String key) {
    Map<String, Object> metadata = document.getMetadata();
    if (metadata == null) {
      return null;
    }
    return metadata.get(key);
  }

  private String asLowerCase(Object value) {
    if (value instanceof String string && StringUtils.hasText(string)) {
      return string.trim().toLowerCase(Locale.ROOT);
    }
    return null;
  }
}
