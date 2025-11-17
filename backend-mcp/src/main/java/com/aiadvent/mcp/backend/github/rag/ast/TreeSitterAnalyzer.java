package com.aiadvent.mcp.backend.github.rag.ast;

import com.aiadvent.mcp.backend.config.GitHubRagProperties;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Loads Tree-sitter native libraries and tracks health of AST-aware chunking.
 * Parsing/AST extraction will be wired in follow-up steps, but we ensure that
 * native artifacts can be discovered and loaded with graceful fallback.
 */
@Service
public class TreeSitterAnalyzer {

  private static final Logger log = LoggerFactory.getLogger(TreeSitterAnalyzer.class);

  private final GitHubRagProperties properties;
  private final TreeSitterLibraryLoader libraryLoader;
  private final AtomicInteger consecutiveFailures = new AtomicInteger();
  private final AtomicBoolean degraded = new AtomicBoolean(false);

  public TreeSitterAnalyzer(GitHubRagProperties properties, TreeSitterLibraryLoader libraryLoader) {
    this.properties = properties;
    this.libraryLoader = libraryLoader;
  }

  public boolean isEnabled() {
    return properties.getAst().isEnabled() && !degraded.get();
  }

  public boolean supportsLanguage(String language) {
    return properties.getAst().getLanguages().stream()
        .anyMatch(entry -> entry.equalsIgnoreCase(language));
  }

  public boolean ensureLanguageLoaded(String language) {
    if (!isEnabled() || !supportsLanguage(language)) {
      return false;
    }
    try {
      Optional<TreeSitterLibraryLoader.LoadedLibrary> loaded = libraryLoader.loadLanguage(language);
      if (loaded.isPresent()) {
        consecutiveFailures.set(0);
        return true;
      }
      handleFailure(language);
    } catch (RuntimeException ex) {
      log.warn("Tree-sitter load failure for {}: {}", language, ex.getMessage());
      handleFailure(language);
    }
    return false;
  }

  public void handleFailure(String language) {
    int failures = consecutiveFailures.incrementAndGet();
    int threshold = properties.getAst().getHealth().getFailureThreshold();
    log.warn(
        "Tree-sitter unavailable for language {} (failure {}/{}). Fallback to heuristics.",
        language,
        failures,
        threshold);
    if (failures >= threshold) {
      degraded.compareAndSet(false, true);
      log.error("Tree-sitter disabled after {} consecutive failures", failures);
    }
  }

  public void resetHealth() {
    consecutiveFailures.set(0);
    degraded.set(false);
  }
}
