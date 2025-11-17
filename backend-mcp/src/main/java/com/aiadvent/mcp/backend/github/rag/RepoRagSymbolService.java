package com.aiadvent.mcp.backend.github.rag;

import com.aiadvent.mcp.backend.github.rag.persistence.RepoRagSymbolGraphEntity;
import com.aiadvent.mcp.backend.github.rag.persistence.RepoRagSymbolGraphRepository;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class RepoRagSymbolService {

  private static final Logger log = LoggerFactory.getLogger(RepoRagSymbolService.class);

  private final RepoRagSymbolGraphRepository repository;
  private final Cache<String, List<SymbolNeighbor>> incomingCache;
  private final Cache<String, List<SymbolNeighbor>> outgoingCache;
  private final Cache<String, Optional<SymbolDefinition>> definitionCache;
  private final Semaphore throttle;
  private final Counter incomingRequests;
  private final Counter outgoingRequests;
  private final Counter definitionRequests;
  private final Counter throttledRequests;

  public RepoRagSymbolService(
      RepoRagSymbolGraphRepository repository, @Nullable MeterRegistry meterRegistry) {
    this.repository = repository;
    this.incomingCache = Caffeine.newBuilder().maximumSize(1000).expireAfterWrite(Duration.ofMinutes(5)).build();
    this.outgoingCache = Caffeine.newBuilder().maximumSize(1000).expireAfterWrite(Duration.ofMinutes(5)).build();
    this.definitionCache = Caffeine.newBuilder().maximumSize(2000).expireAfterWrite(Duration.ofMinutes(10)).build();
    this.throttle = new Semaphore(8);
    MeterRegistry registry = meterRegistry != null ? meterRegistry : new SimpleMeterRegistry();
    this.incomingRequests = registry.counter("github_rag_symbol_requests_total", "type", "incoming");
    this.outgoingRequests = registry.counter("github_rag_symbol_requests_total", "type", "outgoing");
    this.definitionRequests = registry.counter("github_rag_symbol_requests_total", "type", "definition");
    this.throttledRequests = registry.counter("github_rag_symbol_requests_total", "type", "throttled");
  }

  public List<SymbolNeighbor> findCallGraphNeighbors(String namespace, String symbolFqn) {
    if (!hasText(namespace) || !hasText(symbolFqn)) {
      return List.of();
    }
    incomingRequests.increment();
    String key = cacheKey(namespace, symbolFqn);
    return incomingCache.get(key, ignored -> fetchIncoming(namespace, symbolFqn));
  }

  public Optional<SymbolDefinition> findSymbolDefinition(String namespace, String symbolFqn) {
    if (!hasText(namespace) || !hasText(symbolFqn)) {
      return Optional.empty();
    }
    definitionRequests.increment();
    String key = cacheKey(namespace, symbolFqn);
    return definitionCache.get(key, ignored -> fetchDefinition(namespace, symbolFqn));
  }

  public List<SymbolNeighbor> findOutgoingEdges(String namespace, String symbolFqn) {
    if (!hasText(namespace) || !hasText(symbolFqn)) {
      return List.of();
    }
    outgoingRequests.increment();
    String key = cacheKey(namespace, symbolFqn);
    return outgoingCache.get(key, ignored -> fetchOutgoing(namespace, symbolFqn));
  }

  private SymbolNeighbor toNeighbor(RepoRagSymbolGraphEntity entity) {
    return new SymbolNeighbor(
        entity.getFilePath(),
        entity.getChunkIndex(),
        entity.getChunkHash(),
        entity.getRelation(),
        entity.getSymbolFqn(),
        entity.getReferencedSymbolFqn());
  }

  private boolean hasText(String value) {
    return StringUtils.hasText(value);
  }

  private String normalizeSymbol(String symbol) {
    return symbol.trim();
  }

  private List<SymbolNeighbor> fetchIncoming(String namespace, String symbolFqn) {
    return withThrottle(
        () -> {
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
        });
  }

  private List<SymbolNeighbor> fetchOutgoing(String namespace, String symbolFqn) {
    return withThrottle(
        () -> {
          List<RepoRagSymbolGraphEntity> edges =
              repository.findByNamespaceAndSymbolFqn(namespace, normalizeSymbol(symbolFqn));
          if (edges.isEmpty()) {
            log.debug(
                "Call graph outgoing lookup empty (namespace={}, symbol={})",
                namespace,
                symbolFqn);
            return List.of();
          }
          return edges.stream().map(this::toNeighbor).toList();
        });
  }

  private Optional<SymbolDefinition> fetchDefinition(String namespace, String symbolFqn) {
    return withThrottle(
        () -> repository.findByNamespaceAndSymbolFqn(namespace, normalizeSymbol(symbolFqn)).stream()
            .findFirst()
            .map(entity -> new SymbolDefinition(
                entity.getFilePath(),
                entity.getChunkIndex(),
                entity.getChunkHash(),
                entity.getSymbolKind())));
  }

  private <T> T withThrottle(Supplier<T> supplier) {
    boolean acquired = false;
    try {
      acquired = throttle.tryAcquire(1, TimeUnit.SECONDS);
      if (!acquired) {
        log.warn("Symbol graph lookup throttled");
        throttledRequests.increment();
        return supplier.get();
      }
      return supplier.get();
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while waiting for symbol graph lookup", ex);
    } finally {
      if (acquired) {
        throttle.release();
      }
    }
  }

  private String cacheKey(String namespace, String symbol) {
    return namespace + "::" + normalizeSymbol(symbol).toLowerCase(Locale.ROOT);
  }

  public record SymbolNeighbor(
      String filePath,
      int chunkIndex,
      String chunkHash,
      String relation,
      String symbolFqn,
      String referencedSymbolFqn) {}

  public record SymbolDefinition(
      String filePath, int chunkIndex, String chunkHash, String symbolKind) {}
}
