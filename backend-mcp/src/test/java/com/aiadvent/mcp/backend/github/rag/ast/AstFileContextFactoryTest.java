package com.aiadvent.mcp.backend.github.rag.ast;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aiadvent.mcp.backend.github.rag.chunking.AstFileContext;
import com.aiadvent.mcp.backend.github.rag.chunking.AstSymbolMetadata;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class AstFileContextFactoryTest {

  private AstFileContextFactory factory;

  @BeforeEach
  void setUp() {
    TreeSitterAnalyzer analyzer = mock(TreeSitterAnalyzer.class);
    LanguageRegistry languageRegistry = mock(LanguageRegistry.class);
    TreeSitterQueryRegistry queryRegistry = mock(TreeSitterQueryRegistry.class);
    TreeSitterLibraryLoader loader = mock(TreeSitterLibraryLoader.class);

    factory =
        new AstFileContextFactory(
            languageRegistry, queryRegistry, new TreeSitterParser(loader, languageRegistry), analyzer);

    when(analyzer.isEnabled()).thenReturn(true);
    when(analyzer.supportsLanguage(anyString())).thenReturn(true);
    when(analyzer.ensureLanguageLoaded(anyString())).thenReturn(true);
    when(analyzer.isNativeEnabled()).thenReturn(false);
    when(languageRegistry.language(anyString())).thenReturn(java.util.Optional.empty());
  }

  @Test
  void extractsSymbolsFromJavaFile() throws IOException {
    Path source =
        Path.of("src/test/resources/mini-repos/java/src/main/java/com/example/DemoService.java");
    String content = Files.readString(source);

    AstFileContext context =
        factory.create(source, "src/main/java/com/example/DemoService.java", "java", content);

    assertThat(context).isNotNull();
    assertThat(context.symbols()).isNotEmpty();
    AstSymbolMetadata clazz =
        context.symbols().stream().filter(symbol -> symbol.symbolKind().equals("class")).findFirst().orElseThrow();
    assertThat(clazz.docstring()).isNotNull();
  }

  @Test
  void addsFileLevelSymbolEvenWhenOtherSymbolsPresent() {
    String relativePath = "src/main/java/com/example/DemoService.java";
    String content =
        "package com.example;\n"
            + "import java.util.List;\n"
            + "public class DemoService {\n"
            + "  public void process() {}\n"
            + "}\n";

    AstFileContext context =
        factory.create(Path.of("DemoService.java"), relativePath, "java", content);

    assertThat(context).isNotNull();
    assertThat(context.symbols()).isNotEmpty();
    AstSymbolMetadata fileSymbol = context.symbols().get(0);
    assertThat(fileSymbol.symbolKind()).isEqualTo("file");
  }

  @Test
  void buildsFqnWithArgs() {
    String relativePath = "src/main/java/com/example/Demo.java";
    String content =
        "package com.example;\n"
            + "public class Demo {\n"
            + "  public void process(String name, int count) {\n"
            + "    helper();\n"
            + "  }\n"
            + "}\n";

    AstFileContext context =
        factory.create(Path.of("Demo.java"), relativePath, "java", content);

    assertThat(context).isNotNull();
    assertThat(context.symbols())
        .anySatisfy(
            symbol -> {
              if ("method".equals(symbol.symbolKind())) {
                assertThat(symbol.symbolFqn()).isEqualTo("com.example.Demo#process(Stringname,intcount)");
              }
            });
  }

  @ParameterizedTest
  @MethodSource("fixtureFiles")
  void parsesMiniRepoFixtures(Fixture fixture) throws IOException {
    Path source =
        Path.of("src/test/resources/mini-repos", fixture.fixtureName(), fixture.relativeFile());
    String content = Files.readString(source);
    AstFileContext context =
        factory.create(source, fixture.relativeFile(), fixture.language(), content);
    assertThat(context).isNotNull();
    assertThat(context.symbols()).isNotEmpty();
  }

  private static Stream<Fixture> fixtureFiles() {
    return Stream.of(
        new Fixture("java", "java", "src/main/java/com/example/DemoService.java"),
        new Fixture("typescript", "typescript", "src/app.ts"),
        new Fixture("python", "python", "src/demo_service.py"),
        new Fixture("go", "go", "cmd/demo/main.go"),
        new Fixture("kotlin", "kotlin", "src/main/kotlin/com/example/DemoService.kt"),
        new Fixture("javascript", "javascript", "src/app.js"));
  }

  private record Fixture(String language, String fixtureName, String relativeFile) {}
}
