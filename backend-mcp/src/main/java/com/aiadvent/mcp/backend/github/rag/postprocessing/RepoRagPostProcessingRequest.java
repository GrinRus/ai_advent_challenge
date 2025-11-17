package com.aiadvent.mcp.backend.github.rag.postprocessing;

public record RepoRagPostProcessingRequest(
    int maxContextTokens,
    String locale,
    int maxSnippetLines,
    boolean compressionEnabled,
    int rerankTopN,
    boolean codeAwareEnabled,
    double codeAwareHeadMultiplier,
    String requestedLanguage,
    boolean neighborEnabled,
    int neighborRadius,
    int neighborLimit,
    NeighborStrategy neighborStrategy,
    String namespace,
    boolean namespaceAstReady) {

  public enum NeighborStrategy {
    OFF,
    LINEAR,
    PARENT_SYMBOL,
    CALL_GRAPH
  }
}
