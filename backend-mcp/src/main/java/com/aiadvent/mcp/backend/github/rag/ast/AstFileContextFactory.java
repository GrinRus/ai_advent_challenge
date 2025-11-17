package com.aiadvent.mcp.backend.github.rag.ast;

import com.aiadvent.mcp.backend.github.rag.chunking.AstFileContext;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class AstFileContextFactory {

  private static final Logger log = LoggerFactory.getLogger(AstFileContextFactory.class);

  private final TreeSitterAnalyzer analyzer;

  public AstFileContextFactory(TreeSitterAnalyzer analyzer) {
    this.analyzer = analyzer;
  }

  public Supplier<AstFileContext> supplier(
      Path absolutePath, String relativePath, String language, String content) {
    Objects.requireNonNull(absolutePath, "absolutePath");
    return () -> create(absolutePath, relativePath, language, content);
  }

  public AstFileContext create(Path absolutePath, String relativePath, String language, String content) {
    if (!StringUtils.hasText(language)) {
      log.debug("AST fallback: language missing for file {}", relativePath);
      return null;
    }
    if (!analyzer.isEnabled()) {
      log.debug("AST fallback: analyzer disabled, using heuristics for {} ({})", relativePath, language);
      return null;
    }
    if (!analyzer.supportsLanguage(language)) {
      log.debug("AST fallback: language {} not supported for {}", language, relativePath);
      return null;
    }
    if (!analyzer.ensureLanguageLoaded(language)) {
      log.debug("AST fallback: unable to load Tree-sitter grammar for {} ({})", relativePath, language);
      return null;
    }
    log.debug("Tree-sitter AST extraction not yet implemented for {} ({}), falling back", language, relativePath);
    return null;
  }

  public Optional<AstFileContext> optional(Path absolutePath, String relativePath, String language, String content) {
    return Optional.ofNullable(create(absolutePath, relativePath, language, content));
  }
}
