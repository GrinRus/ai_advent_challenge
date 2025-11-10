package com.aiadvent.mcp.backend.github.rag;

import java.util.List;
import java.util.Objects;

/**
 * Declarative filter definition for repo RAG search.
 */
public record RepoRagSearchFilters(List<String> languages, List<String> pathGlobs) {

  public RepoRagSearchFilters {
    languages = normalize(languages);
    pathGlobs = normalize(pathGlobs);
  }

  public boolean hasLanguages() {
    return !languages.isEmpty();
  }

  public boolean hasPathGlobs() {
    return !pathGlobs.isEmpty();
  }

  private static List<String> normalize(List<String> source) {
    if (source == null || source.isEmpty()) {
      return List.of();
    }
    return source.stream()
        .filter(Objects::nonNull)
        .map(String::trim)
        .filter(value -> !value.isEmpty())
        .distinct()
        .toList();
  }
}
