package com.aiadvent.mcp.backend.github.rag.ast;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aiadvent.mcp.backend.config.GitHubRagProperties;
import com.aiadvent.mcp.backend.github.rag.chunking.AstFileContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

class AstFileContextFactoryNativeModeTest {

  private static final Path JAVA_FILE =
      Path.of("src/test/resources/mini-repos/java/src/main/java/com/example/DemoService.java");

  @Test
  void fallsBackGracefullyWhenNativeLanguageMissing() throws Exception {
    GitHubRagProperties props = new GitHubRagProperties();
    props.getAst().setEnabled(true);
    props.getAst().setNativeEnabled(true);
    props.getAst().setLanguages(List.of("java"));

    TreeSitterLibraryLoader loader = mock(TreeSitterLibraryLoader.class);
    when(loader.loadLanguage("java")).thenReturn(Optional.empty());
    LanguageRegistry languageRegistry = spy(new LanguageRegistry(loader));
    TreeSitterQueryRegistry queryRegistry = new TreeSitterQueryRegistry();
    TreeSitterParser parser = new TreeSitterParser(loader, languageRegistry);
    TreeSitterAnalyzer analyzer = new TreeSitterAnalyzer(props, loader);
    AstFileContextFactory factory =
        new AstFileContextFactory(languageRegistry, queryRegistry, parser, analyzer);

    String content = Files.readString(JAVA_FILE);
    AstFileContext context =
        factory.create(JAVA_FILE, "src/main/java/com/example/DemoService.java", "java", content);

    assertThat(context).isNotNull();
    assertThat(context.symbols()).isNotEmpty();
    verify(languageRegistry, never()).language("java");
    assertThat(analyzer.isEnabled()).isTrue();
  }

  @Test
  void enablesNativeParsingWhenLibrariesAvailable() throws Exception {
    GitHubRagProperties props = new GitHubRagProperties();
    props.getAst().setEnabled(true);
    props.getAst().setNativeEnabled(true);
    props.getAst().setLanguages(List.of("java"));

    TreeSitterLibraryLoader loader =
        new TreeSitterLibraryLoader(props, new DefaultResourceLoader());
    assumeTrue(loader.ensureCoreLibraryLoaded(), "libjava-tree-sitter not available for platform");
    LanguageRegistry languageRegistry = spy(new LanguageRegistry(loader));
    TreeSitterQueryRegistry queryRegistry = new TreeSitterQueryRegistry();
    TreeSitterParser parser = new TreeSitterParser(loader, languageRegistry);
    TreeSitterAnalyzer analyzer = new TreeSitterAnalyzer(props, loader);
    AstFileContextFactory factory =
        new AstFileContextFactory(languageRegistry, queryRegistry, parser, analyzer);

    String content = Files.readString(JAVA_FILE);
    AstFileContext context =
        factory.create(JAVA_FILE, "src/main/java/com/example/DemoService.java", "java", content);

    assertThat(context).isNotNull();
    assertThat(context.symbols())
        .anySatisfy(
            symbol ->
                assertThat(symbol.symbolFqn())
                    .startsWith("com.example.DemoService"));
    assertThat(context.symbols())
        .anySatisfy(
            symbol ->
                assertThat(symbol.callsOut())
                    .contains("com.example.repository.UserRepository#findUser"));
    verify(languageRegistry, atLeastOnce()).language("java");
    assertThat(analyzer.isNativeEnabled()).isTrue();
  }
}
