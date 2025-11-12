package com.aiadvent.mcp.backend.github.rag;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.util.StringUtils;

/**
 * Declarative filter definition for repo RAG search.
 */
public record RepoRagSearchFilters(List<String> languages, List<String> pathGlobs) {

  private static final Set<String> UNIVERSAL_GLOBS =
      Set.of("*", "**", "**/*", "*/**", "./**/*");

  public RepoRagSearchFilters {
    languages = normalizeLanguages(languages);
    pathGlobs = normalizePathGlobs(pathGlobs);
  }

  public boolean hasLanguages() {
    return !languages.isEmpty();
  }

  public boolean hasPathGlobs() {
    return !pathGlobs.isEmpty();
  }

  private static List<String> normalizeLanguages(List<String> source) {
    return normalizeDistinct(source, true).stream()
        .map(value -> value.toLowerCase(Locale.ROOT))
        .toList();
  }

  private static List<String> normalizePathGlobs(List<String> source) {
    return normalizeDistinct(source, false).stream()
        .filter(value -> !isUniversalGlob(value))
        .toList();
  }

  private static List<String> normalizeDistinct(List<String> source, boolean lowerCase) {
    if (source == null || source.isEmpty()) {
      return List.of();
    }
    LinkedHashSet<String> uniq = new LinkedHashSet<>();
    for (String candidate : source) {
      if (candidate == null) {
        continue;
      }
      String normalized = candidate.trim();
      if (normalized.isEmpty()) {
        continue;
      }
      uniq.add(lowerCase ? normalized.toLowerCase(Locale.ROOT) : normalized);
    }
    return List.copyOf(uniq);
  }

  private static boolean isUniversalGlob(String glob) {
    if (!StringUtils.hasText(glob)) {
      return true;
    }
    String normalized = glob.trim();
    return UNIVERSAL_GLOBS.contains(normalized)
        || UNIVERSAL_GLOBS.contains(normalized.toLowerCase(Locale.ROOT));
  }
}
