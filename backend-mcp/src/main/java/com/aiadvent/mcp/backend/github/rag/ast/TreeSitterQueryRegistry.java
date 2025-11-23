package com.aiadvent.mcp.backend.github.rag.ast;

import io.github.treesitter.jtreesitter.Language;
import io.github.treesitter.jtreesitter.Query;
import io.github.treesitter.jtreesitter.QueryError;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Lazy loader for Tree-sitter queries per language. If queries are absent or invalid, callers
 * should fallback to heuristic extraction.
 */
@Component
public class TreeSitterQueryRegistry {

  private static final Logger log = LoggerFactory.getLogger(TreeSitterQueryRegistry.class);

  public record LanguageQueries(Optional<Query> symbols, Optional<Query> calls, Optional<Query> heritage, Optional<Query> fields) {}

  private final Map<String, LanguageQueries> cache = new ConcurrentHashMap<>();

  public LanguageQueries queries(Language language, String languageId) {
    return cache.computeIfAbsent(
        languageId,
        key -> new LanguageQueries(
            loadQuery(language, key, "symbols.scm"),
            loadQuery(language, key, "calls.scm"),
            loadQuery(language, key, "heritage.scm"),
            loadQuery(language, key, "fields.scm")));
  }

  public static LanguageQueries empty() {
    return new LanguageQueries(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
  }

  private Optional<Query> loadQuery(Language language, String languageId, String fileName) {
    String path = "treesitter/queries/" + languageId + "/" + fileName;
    ClassPathResource resource = new ClassPathResource(path);
    if (!resource.exists()) {
      return Optional.empty();
    }
    try (InputStream input = resource.getInputStream()) {
      String source = new String(input.readAllBytes());
      return Optional.of(new Query(language, source));
    } catch (QueryError ex) {
      log.warn("Failed to load Tree-sitter query {} for {}: {}", fileName, languageId, ex.getMessage());
      return Optional.empty();
    } catch (IOException | RuntimeException ex) {
      log.warn("Failed to load Tree-sitter query {} for {}: {}", fileName, languageId, ex.getMessage());
      return Optional.empty();
    }
  }
}
