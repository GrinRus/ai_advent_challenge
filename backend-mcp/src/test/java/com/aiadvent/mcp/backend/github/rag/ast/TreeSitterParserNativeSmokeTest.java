package com.aiadvent.mcp.backend.github.rag.ast;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.aiadvent.mcp.backend.github.rag.chunking.AstFileContext;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

class TreeSitterParserNativeSmokeTest {

  @Test
  void parsesJavaWithNativeBinding() throws Exception {
    GitHubRagPropertiesStub properties = new GitHubRagPropertiesStub();
    TreeSitterLibraryLoader loader =
        new TreeSitterLibraryLoader(properties, new DefaultResourceLoader());
    assumeTrue(loader.ensureCoreLibraryLoaded(), "libjava-tree-sitter not available");

    LanguageRegistry languageRegistry = new LanguageRegistry(loader);
    TreeSitterParser parser = new TreeSitterParser(loader, languageRegistry);
    String content =
        Files.readString(
            Path.of("src/test/resources/mini-repos/java/src/main/java/com/example/DemoService.java"));

    AstFileContext ctx =
        parser
            .parse(content, "java", "src/main/java/com/example/DemoService.java", true)
            .orElse(null);

    assertThat(ctx).isNotNull();
    assertThat(ctx.symbols())
        .anySatisfy(
            symbol ->
                assertThat(symbol.symbolFqn())
                    .as("method FQN")
                    .isEqualTo("com.example.DemoService#process(Stringname,intcount)"));
  }

  private static final class GitHubRagPropertiesStub
      extends com.aiadvent.mcp.backend.config.GitHubRagProperties {
    GitHubRagPropertiesStub() {
      getAst().setEnabled(true);
      getAst().setNativeEnabled(true);
      getAst().setLanguages(java.util.List.of("java"));
    }
  }
}
