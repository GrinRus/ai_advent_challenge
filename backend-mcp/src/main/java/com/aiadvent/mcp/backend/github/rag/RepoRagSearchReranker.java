package com.aiadvent.mcp.backend.github.rag;

import java.util.List;

public interface RepoRagSearchReranker {

  /**
   * Reorders the provided matches in-place according to the rerank strategy.
   *
   * @return {@code true} if reranking was applied, {@code false} otherwise.
   */
  boolean rerank(String query, List<RepoRagSearchService.SearchMatch> matches, int topN);
}
