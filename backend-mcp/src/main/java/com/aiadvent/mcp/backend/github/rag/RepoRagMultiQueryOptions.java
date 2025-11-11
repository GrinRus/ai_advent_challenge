package com.aiadvent.mcp.backend.github.rag;

/**
 * Declarative overrides for the multi-query expander.
 */
public record RepoRagMultiQueryOptions(Boolean enabled, Integer queries, Integer maxQueries) {}
