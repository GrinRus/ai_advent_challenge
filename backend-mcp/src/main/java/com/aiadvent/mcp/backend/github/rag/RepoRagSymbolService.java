package com.aiadvent.mcp.backend.github.rag;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Placeholder service for symbol-level metadata. The full implementation will arrive with
 * AST-aware indexing (Wave 33+). For now it returns empty results so that neighbor strategies can
 * safely fall back without breaking the pipeline.
 */
@Service
public class RepoRagSymbolService {

  private static final Logger log = LoggerFactory.getLogger(RepoRagSymbolService.class);

  public List<SymbolNeighbor> findCallGraphNeighbors(String namespace, String symbolFqn) {
    log.debug(
        "Call graph neighbor lookup requested for namespace={} symbol={}",
        namespace,
        symbolFqn);
    return List.of();
  }

  public record SymbolNeighbor(String filePath, int chunkIndex, String relation) {}
}
