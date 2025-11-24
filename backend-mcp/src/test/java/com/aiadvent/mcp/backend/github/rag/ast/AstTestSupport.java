package com.aiadvent.mcp.backend.github.rag.ast;

import com.aiadvent.mcp.backend.config.GitHubRagProperties;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.ResourceLoader;

/**
 * Helper for wiring Tree-sitter components in unit tests without relying on mocks.
 */
public final class AstTestSupport {

  private AstTestSupport() {}

  public static AstComponents astComponents(GitHubRagProperties properties) {
    return astComponents(properties, new DefaultResourceLoader());
  }

  public static AstComponents astComponents(
      GitHubRagProperties properties, ResourceLoader resourceLoader) {
    TreeSitterLibraryLoader loader = new TreeSitterLibraryLoader(properties, resourceLoader);
    LanguageRegistry languageRegistry = new LanguageRegistry(loader);
    TreeSitterQueryRegistry queryRegistry = new TreeSitterQueryRegistry();
    TreeSitterAnalyzer analyzer = new TreeSitterAnalyzer(properties, loader);
    TreeSitterParser parser = new TreeSitterParser(loader, languageRegistry, queryRegistry);
    AstFileContextFactory factory = new AstFileContextFactory(parser, analyzer);
    return new AstComponents(loader, languageRegistry, queryRegistry, analyzer, parser, factory);
  }

  public record AstComponents(
      TreeSitterLibraryLoader loader,
      LanguageRegistry languageRegistry,
      TreeSitterQueryRegistry queryRegistry,
      TreeSitterAnalyzer analyzer,
      TreeSitterParser parser,
      AstFileContextFactory factory) {}
}
