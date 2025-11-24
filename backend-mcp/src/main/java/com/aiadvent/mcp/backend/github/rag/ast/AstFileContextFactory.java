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

  private final TreeSitterParser parser;
  private final TreeSitterAnalyzer analyzer;

  public AstFileContextFactory(
      TreeSitterParser parser,
      TreeSitterAnalyzer analyzer) {
    this.parser = parser;
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
    if (analyzer == null) {
      return parser.parse(content, language, relativePath, true).orElse(null);
    }
    if (!analyzer.isEnabled() || !analyzer.supportsLanguage(language)) {
      log.debug(
          "AST fallback: Tree-sitter disabled or unsupported language {} for {}",
          language,
          relativePath);
      return parser.parse(content, language, relativePath, false).orElse(null);
    }
    boolean nativeEnabled = false;
    if (analyzer.isNativeEnabled()) {
      nativeEnabled = analyzer.ensureLanguageLoaded(language);
    }
    return parser.parse(content, language, relativePath, nativeEnabled).orElse(null);
  }

  public Optional<AstFileContext> optional(
      Path absolutePath, String relativePath, String language, String content) {
    return Optional.ofNullable(create(absolutePath, relativePath, language, content));
  }

}
