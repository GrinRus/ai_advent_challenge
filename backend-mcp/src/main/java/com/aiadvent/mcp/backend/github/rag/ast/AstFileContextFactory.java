package com.aiadvent.mcp.backend.github.rag.ast;

import com.aiadvent.mcp.backend.github.rag.chunking.AstFileContext;
import com.aiadvent.mcp.backend.github.rag.chunking.AstSymbolMetadata;
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

  private final LanguageRegistry languageRegistry;
  private final TreeSitterQueryRegistry queryRegistry;
  private final TreeSitterParser parser;
  private final TreeSitterAnalyzer analyzer;

  public AstFileContextFactory(
      LanguageRegistry languageRegistry,
      TreeSitterQueryRegistry queryRegistry,
      TreeSitterParser parser,
      TreeSitterAnalyzer analyzer) {
    this.languageRegistry = languageRegistry;
    this.queryRegistry = queryRegistry;
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
    if (analyzer != null && (!analyzer.isEnabled() || !analyzer.supportsLanguage(language))) {
      log.debug("AST fallback: Tree-sitter disabled or unsupported language {} for {}", language, relativePath);
      return parser.parse(content, language, relativePath, false, TreeSitterQueryRegistry.empty()).orElse(null);
    }
    boolean nativeEnabled =
        analyzer == null ? true : analyzer.ensureLanguageLoaded(language) && analyzer.isNativeEnabled();
    return parser.parse(content, language, relativePath, nativeEnabled, queries(language)).orElse(null);
  }

  public Optional<AstFileContext> optional(
      Path absolutePath, String relativePath, String language, String content) {
    return Optional.ofNullable(create(absolutePath, relativePath, language, content));
  }

  private TreeSitterQueryRegistry.LanguageQueries queries(String language) {
    return languageRegistry
        .language(language)
        .map(lang -> queryRegistry.queries(lang, language.toLowerCase(java.util.Locale.ROOT)))
        .orElse(TreeSitterQueryRegistry.empty());
  }
}
