package com.aiadvent.mcp.backend.github.rag.ast;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aiadvent.mcp.backend.github.rag.chunking.AstFileContext;
import com.aiadvent.mcp.backend.github.rag.chunking.AstSymbolMetadata;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AstFileContextFactoryTest {

  private TreeSitterAnalyzer analyzer;
  private AstFileContextFactory factory;

  @BeforeEach
  void setUp() {
    analyzer = mock(TreeSitterAnalyzer.class);
    factory = new AstFileContextFactory(analyzer, new TreeSitterParser());
    when(analyzer.isEnabled()).thenReturn(true);
    when(analyzer.supportsLanguage("java")).thenReturn(true);
    when(analyzer.ensureLanguageLoaded("java")).thenReturn(true);
  }

  @Test
  void extractsSymbolsFromJavaFile() {
    String relativePath = "src/main/java/com/example/DemoService.java";
    String content =
        "package com.example;\n" +
        "import java.util.List;\n" +
        "\n" +
        "/** Demo service */\n" +
        "public class DemoService {\n" +
        "  public void process() {\n" +
        "    helper();\n" +
        "  }\n" +
        "}\n";

    AstFileContext context =
        factory.create(Path.of("DemoService.java"), relativePath, "java", content);

    assertThat(context).isNotNull();
    assertThat(context.symbols()).isNotEmpty();
    AstSymbolMetadata clazz =
        context.symbols().stream().filter(symbol -> symbol.symbolKind().equals("class")).findFirst().orElseThrow();
    assertThat(clazz.docstring()).isEqualTo("Demo service");
    AstSymbolMetadata method =
        context.symbols().stream().filter(symbol -> symbol.symbolKind().equals("method")).findFirst().orElseThrow();
    assertThat(method.callsOut()).contains("helper");
    assertThat(method.imports()).contains("import java.util.List;");
    assertThat(method.symbolFqn()).contains("DemoService");
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
    assertThat(fileSymbol.lineStart()).isEqualTo(1);
    assertThat(fileSymbol.lineEnd()).isGreaterThanOrEqualTo(5);
    assertThat(fileSymbol.symbolFqn()).isEqualTo("com.example.src.main.java.com.example.DemoService.java");
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
}
