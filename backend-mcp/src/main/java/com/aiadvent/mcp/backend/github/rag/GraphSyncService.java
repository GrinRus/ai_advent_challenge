package com.aiadvent.mcp.backend.github.rag;

import com.aiadvent.mcp.backend.config.GitHubRagProperties;
import com.aiadvent.mcp.backend.github.rag.chunking.AstSymbolMetadata;
import com.aiadvent.mcp.backend.github.rag.chunking.Chunk;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;
import org.neo4j.driver.summary.ResultSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

@Service
@ConditionalOnBean(Driver.class)
public class GraphSyncService {

  private static final Logger log = LoggerFactory.getLogger(GraphSyncService.class);

  private static final String MERGE_FILE_QUERY =
      """
      MERGE (repo:Repo {namespace:$namespace})
      MERGE (repo)-[:CONTAINS]->(file:File {namespace:$namespace, path:$filePath})
      SET file.updatedAt = $updatedAt
      """;

  private static final String DELETE_STALE_SYMBOLS_QUERY =
      """
      MATCH (file:File {namespace:$namespace, path:$filePath})-[:DECLARES]->(symbol:Symbol)
      WHERE NOT symbol.fqn IN $activeFqns
      DETACH DELETE symbol
      """;

  private static final String UPSERT_SYMBOLS_QUERY =
      """
      UNWIND $symbols AS symbol
      MATCH (file:File {namespace:$namespace, path:$filePath})
      MERGE (file)-[:DECLARES]->(node:Symbol {namespace:$namespace, fqn:symbol.fqn})
      SET node.symbolKind = symbol.kind,
          node.symbolVisibility = symbol.visibility,
          node.symbolSignature = symbol.signature,
          node.docstring = symbol.docstring,
          node.lineStart = symbol.lineStart,
          node.lineEnd = symbol.lineEnd,
          node.filePath = $filePath,
          node.updatedAt = $updatedAt
      """;

  private static final String DELETE_FILE_EDGES_QUERY =
      """
      MATCH (file:File {namespace:$namespace, path:$filePath})-[:DECLARES]->(symbol:Symbol)-[rel {namespace:$namespace}]->()
      DELETE rel
      """;

  private final Driver driver;
  private final GitHubRagProperties.Graph graphProperties;
  private final Counter syncSuccessCounter;
  private final Counter syncFailureCounter;
  private final Counter edgesWrittenCounter;
  private final Counter nodesWrittenCounter;
  private final Timer syncTimer;

  public GraphSyncService(
      Driver driver, GitHubRagProperties properties, @Nullable MeterRegistry meterRegistry) {
    this.driver = driver;
    this.graphProperties = properties.getGraph();
    MeterRegistry registry = meterRegistry != null ? meterRegistry : new SimpleMeterRegistry();
    this.syncSuccessCounter = registry.counter("graph_sync_success_total");
    this.syncFailureCounter = registry.counter("graph_sync_failure_total");
    this.edgesWrittenCounter = registry.counter("graph_edges_written_total");
    this.nodesWrittenCounter = registry.counter("graph_nodes_written_total");
    this.syncTimer = registry.timer("graph_sync_duration_ms");
  }

  public void syncFile(String namespace, String filePath, List<Chunk> chunks) {
    if (CollectionUtils.isEmpty(chunks)) {
      deleteFile(namespace, filePath);
      return;
    }
    Map<String, GraphSymbol> symbols = collectSymbols(chunks);
    List<Map<String, Object>> edges = collectEdges(chunks);
    if (symbols.isEmpty()) {
      deleteFile(namespace, filePath);
      return;
    }
    runWithRetry(() -> doSync(namespace, filePath, symbols, edges));
  }

  public void deleteFile(String namespace, String filePath) {
    SessionConfig sessionConfig =
        SessionConfig.builder().withDatabase(graphProperties.getDatabase()).build();
    try (Session session = driver.session(sessionConfig)) {
      session.executeWrite(
          tx -> {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("namespace", namespace);
            params.put("filePath", filePath);
            tx.run(
                    """
                    MATCH (file:File {namespace:$namespace, path:$filePath})-[:DECLARES]->(symbol:Symbol)
                    DETACH DELETE symbol
                    """,
                    params)
                .consume();
            tx.run(
                    """
                    MATCH (:Repo {namespace:$namespace})-[:CONTAINS]->(file:File {namespace:$namespace, path:$filePath})
                    DETACH DELETE file
                    """,
                    params)
                .consume();
            return null;
          });
    } catch (RuntimeException ex) {
      log.warn("Failed to delete graph nodes for {}: {}", filePath, ex.getMessage());
    }
  }

  private Map<String, GraphSymbol> collectSymbols(List<Chunk> chunks) {
    Map<String, GraphSymbol> symbols = new LinkedHashMap<>();
    for (Chunk chunk : chunks) {
      AstSymbolMetadata ast = chunk.astMetadata();
      if (ast == null || !StringUtils.hasText(ast.symbolFqn())) {
        continue;
      }
      symbols.computeIfAbsent(
          normalize(ast.symbolFqn()),
          key ->
              new GraphSymbol(
                  key,
                  normalize(ast.symbolKind()),
                  normalize(ast.symbolVisibility()),
                  normalizeSignature(ast.symbolSignature()),
                  normalize(ast.docstring()),
                  chunk.lineStart(),
                  chunk.lineEnd()));
    }
    return symbols;
  }

  private List<Map<String, Object>> collectEdges(List<Chunk> chunks) {
    List<Map<String, Object>> edges = new ArrayList<>();
    Set<String> dedup = new LinkedHashSet<>();
    for (int i = 0; i < chunks.size(); i++) {
      Chunk chunk = chunks.get(i);
      AstSymbolMetadata ast = chunk.astMetadata();
      if (ast == null || !StringUtils.hasText(ast.symbolFqn())) {
        continue;
      }
      String source = normalize(ast.symbolFqn());
      appendEdges(edges, dedup, source, ast.callsOut(), "CALLS", chunk, i);
      appendEdges(edges, dedup, source, ast.implementsTypes(), "IMPLEMENTS", chunk, i);
      appendEdges(edges, dedup, source, ast.readsFields(), "READS_FIELD", chunk, i);
      appendEdges(edges, dedup, source, ast.usesTypes(), "USES_TYPE", chunk, i);
    }
    return edges;
  }

  private void appendEdges(
      List<Map<String, Object>> target,
      Set<String> dedup,
      String source,
      List<String> references,
      String relation,
      Chunk chunk,
      int chunkIndex) {
    if (CollectionUtils.isEmpty(references) || !StringUtils.hasText(source)) {
      return;
    }
    for (String reference : references) {
      if (!StringUtils.hasText(reference)) {
        continue;
      }
      String normalizedTarget = normalize(reference);
      String key = relation + ":" + source + "->" + normalizedTarget;
      if (dedup.add(key)) {
        Map<String, Object> edge =
            Map.of(
                "sourceFqn", source,
                "targetFqn", normalizedTarget,
                "chunkHash", chunk.hash(),
                "chunkIndex", chunkIndex,
                "relation", relation);
        target.add(edge);
      }
    }
  }

  private String normalize(String value) {
    return value == null ? null : value.trim();
  }

  private String normalizeSignature(String signature) {
    if (!StringUtils.hasText(signature)) {
      return null;
    }
    return signature.trim().replaceAll("\\s+", " ");
  }

  private void doSync(
      String namespace,
      String filePath,
      Map<String, GraphSymbol> symbols,
      List<Map<String, Object>> edges) {
    Instant startedAt = Instant.now();
    Instant updatedAt = Instant.now();
    SessionConfig sessionConfig =
        SessionConfig.builder().withDatabase(graphProperties.getDatabase()).build();
    final int batchSize = Math.max(1, graphProperties.getSyncBatchSize());
    SyncStats stats = new SyncStats();
    try (Session session = driver.session(sessionConfig)) {
      session.executeWrite(
          tx -> {
            Map<String, Object> baseParams = new LinkedHashMap<>();
            baseParams.put("namespace", namespace);
            baseParams.put("filePath", filePath);
            baseParams.put("updatedAt", updatedAt.toString());
            tx.run(MERGE_FILE_QUERY, baseParams).consume();

            List<String> activeFqns = new ArrayList<>(symbols.keySet());
            Map<String, Object> staleParams = new LinkedHashMap<>(baseParams);
            staleParams.put(
                "activeFqns", activeFqns.isEmpty() ? Collections.singletonList("__none__") : activeFqns);
            ResultSummary staleSummary = tx.run(DELETE_STALE_SYMBOLS_QUERY, staleParams).consume();
            stats.nodesRemoved += staleSummary.counters().nodesDeleted();

            for (List<GraphSymbol> batch : partition(new ArrayList<>(symbols.values()), batchSize)) {
              Map<String, Object> symbolParams = new LinkedHashMap<>(baseParams);
              List<Map<String, Object>> payload = new ArrayList<>(batch.size());
              for (GraphSymbol symbol : batch) {
                payload.add(symbol.toMap());
              }
              symbolParams.put("symbols", payload);
              ResultSummary summary = tx.run(UPSERT_SYMBOLS_QUERY, symbolParams).consume();
              stats.nodesInserted += summary.counters().nodesCreated();
              stats.symbolBatches++;
            }

            ResultSummary deleteEdgesSummary =
                tx.run(DELETE_FILE_EDGES_QUERY, baseParams).consume();
            stats.edgesRemoved += deleteEdgesSummary.counters().relationshipsDeleted();

            Map<String, List<Map<String, Object>>> edgesByRelation = new LinkedHashMap<>();
            for (Map<String, Object> edge : edges) {
              String relation = (String) edge.getOrDefault("relation", "CALLS");
              edgesByRelation.computeIfAbsent(relation, key -> new ArrayList<>()).add(edge);
            }

            for (Map.Entry<String, List<Map<String, Object>>> entry : edgesByRelation.entrySet()) {
              List<Map<String, Object>> relationEdges = entry.getValue();
              if (relationEdges.isEmpty()) {
                continue;
              }
              String relation = entry.getKey();
              String upsertQuery = buildUpsertEdgeQuery(relation);
              for (List<Map<String, Object>> batch : partition(relationEdges, batchSize)) {
                Map<String, Object> edgeParams = new LinkedHashMap<>(baseParams);
                edgeParams.put("edges", batch);
                ResultSummary summary = tx.run(upsertQuery, edgeParams).consume();
                stats.edgesInserted += summary.counters().relationshipsCreated();
                stats.edgeBatches++;
              }
            }
            return null;
          });
    }
    Duration duration = Duration.between(startedAt, Instant.now());
    syncSuccessCounter.increment();
    if (stats.nodesInserted > 0) {
      nodesWrittenCounter.increment(stats.nodesInserted);
    }
    if (stats.edgesInserted > 0) {
      edgesWrittenCounter.increment(stats.edgesInserted);
    }
    syncTimer.record(duration);
    log.info(
        "Neo4j graph synced (namespace={}, file={}, symbols={}, edges={}, removedSymbols={}, removedEdges={}, symbolBatches={}, edgeBatches={}, durationMs={})",
        namespace,
        filePath,
        symbols.size(),
        edges.size(),
        stats.nodesRemoved,
        stats.edgesRemoved,
        stats.symbolBatches,
        stats.edgeBatches,
        duration.toMillis());
  }

  private String buildUpsertEdgeQuery(String relation) {
    String safeRelation = relation != null ? relation.trim().toUpperCase(Locale.ROOT) : "CALLS";
    // relation name must be a valid Cypher identifier; fallback to CALLS if not
    if (!safeRelation.matches("[A-Z_][A-Z0-9_]*")) {
      safeRelation = "CALLS";
    }
    return """
        UNWIND $edges AS edge
        MATCH (source:Symbol {namespace:$namespace, fqn:edge.sourceFqn})
        MERGE (target:Symbol {namespace:$namespace, fqn:edge.targetFqn})
        MERGE (source)-[rel:%s {namespace:$namespace}]->(target)
        SET rel.updatedAt = $updatedAt,
            rel.chunkHash = edge.chunkHash,
            rel.chunkIndex = edge.chunkIndex
        """
        .formatted(safeRelation);
  }

  private void runWithRetry(Runnable runnable) {
    int attempts = 0;
    RuntimeException last = null;
    int maxAttempts = 3;
    while (attempts < maxAttempts) {
      try {
        runnable.run();
        return;
      } catch (RuntimeException ex) {
        syncFailureCounter.increment();
        last = ex;
        attempts++;
        if (attempts >= maxAttempts) {
          break;
        }
        try {
          TimeUnit.MILLISECONDS.sleep(graphProperties.getSyncRetryDelay().toMillis());
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException("Interrupted while retrying graph sync", ie);
        }
      }
    }
    throw new IllegalStateException(
        "Failed to sync Neo4j graph after %d attempts".formatted(attempts), last);
  }

  private <T> List<List<T>> partition(List<T> source, int batchSize) {
    if (CollectionUtils.isEmpty(source)) {
      return List.of();
    }
    List<List<T>> result = new ArrayList<>();
    for (int index = 0; index < source.size(); index += batchSize) {
      int end = Math.min(source.size(), index + batchSize);
      result.add(source.subList(index, end));
    }
    return result;
  }

  private static final class GraphSymbol {
    String fqn;
    String kind;
    String visibility;
    String signature;
    String docstring;
    int lineStart;
    int lineEnd;

    GraphSymbol(
        String fqn,
        String kind,
        String visibility,
        String signature,
        String docstring,
        int lineStart,
        int lineEnd) {
      this.fqn = Objects.requireNonNullElse(fqn, "");
      this.kind = kind;
      this.visibility = visibility;
      this.signature = signature;
      this.docstring = docstring;
      this.lineStart = lineStart;
      this.lineEnd = lineEnd;
    }

    Map<String, Object> toMap() {
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("fqn", fqn);
      payload.put("kind", kind);
      payload.put("visibility", visibility);
      payload.put("signature", signature);
      payload.put("docstring", docstring);
      payload.put("lineStart", lineStart);
      payload.put("lineEnd", lineEnd);
      return payload;
    }
  }

  private static final class SyncStats {
    long nodesInserted;
    long nodesRemoved;
    long edgesInserted;
    long edgesRemoved;
    int symbolBatches;
    int edgeBatches;
  }
}
