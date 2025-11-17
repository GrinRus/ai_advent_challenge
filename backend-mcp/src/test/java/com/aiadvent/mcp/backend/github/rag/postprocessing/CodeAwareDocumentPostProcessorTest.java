package com.aiadvent.mcp.backend.github.rag.postprocessing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aiadvent.mcp.backend.config.GitHubRagProperties;
import com.aiadvent.mcp.backend.github.rag.RepoRagSymbolService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;

class CodeAwareDocumentPostProcessorTest {

  private GitHubRagProperties.CodeAware codeAware;

  @BeforeEach
  void setUp() {
    GitHubRagProperties properties = new GitHubRagProperties();
    codeAware = properties.getRerank().getCodeAware();
    codeAware.getLanguageBonus().put("java", 1.4d);
    codeAware.getSymbolPriority().put("class", 1.2d);
    codeAware.getSymbolPriority().put("method", 1.0d);
    codeAware.getSymbolPriority().put("default", 1.0d);
    codeAware.getPathPenalty().setPenaltyMultiplier(0.3d);
    codeAware.getPathPenalty().setDenyPrefixes(List.of("generated", "testdata"));
    codeAware.getDiversity().setMaxPerFile(1);
    codeAware.getDiversity().setMaxPerSymbol(2);
  }

  @Test
  void prioritizesMatchingLanguageAndSymbol() {
    CodeAwareDocumentPostProcessor processor =
        new CodeAwareDocumentPostProcessor(codeAware, 1, 2.0, "java");
    Document preferred =
        document("src/main/java/App.java", "java", "class App", "class", "public", 0.62, 10, 40, true, false);
    Document fallback =
        document("scripts/tool.py", "python", "def handle", "function", "public", 0.9, 5, 18, false, false);

    List<Document> reordered =
        processor.process(Query.builder().text("refactor controllers").build(), List.of(fallback, preferred));

    assertThat(reordered).hasSize(2);
    assertThat(reordered.get(0)).isEqualTo(preferred);
  }

  @Test
  void appliesPathPenaltyForGeneratedSources() {
    CodeAwareDocumentPostProcessor processor =
        new CodeAwareDocumentPostProcessor(codeAware, 1, 2.0, "java");
    Document generated =
        document("generated/Main.java", "java", "class Auto", "class", "public", 0.95, 1, 30, false, false);
    Document handcrafted =
        document("src/main/java/App.java", "java", "class App", "class", "public", 0.8, 3, 28, true, false);

    List<Document> reordered =
        processor.process(Query.builder().text("explain models").build(), List.of(generated, handcrafted));

    assertThat(reordered.get(0)).isEqualTo(handcrafted);
  }

  @Test
  void penalizesRepeatedFilesToFavourDiversity() {
    codeAware.getDiversity().setMaxPerFile(1);
    CodeAwareDocumentPostProcessor processor =
        new CodeAwareDocumentPostProcessor(codeAware, 2, 2.0, null);

    Document first =
        document("src/main/java/App.java", "java", "class App", "class", "public", 0.95, 1, 25, true, false);
    Document secondSameFile =
        document("src/main/java/App.java", "java", "method run", "method", "public", 0.92, 30, 60, true, false);
    Document differentFile =
        document("src/main/java/Service.java", "java", "class Service", "class", "internal", 0.85, 5, 40, false, false);

    List<Document> reordered =
        processor.process(
            Query.builder().text("service details").build(),
            List.of(first, secondSameFile, differentFile));

    assertThat(reordered.get(0)).isEqualTo(first);
    assertThat(reordered.get(1)).isEqualTo(differentFile);
  }

  @Test
  void boostsDocumentedSymbolsAndPenalizesUndocumented() {
    CodeAwareDocumentPostProcessor processor =
        new CodeAwareDocumentPostProcessor(codeAware, 2, 2.0, "java");

    Document documented =
        document("src/main/java/App.java", "java", "class App", "class", "public", 0.70, 4, 42, true, false);
    Document undocumented =
        document("src/main/java/Service.java", "java", "class Service", "class", "public", 0.78, 10, 60, false, false);

    List<Document> reordered =
        processor.process(
            Query.builder().text("describe app").build(), List.of(undocumented, documented));

    assertThat(reordered.get(0)).isEqualTo(documented);
  }

  @Test
  void demotesTestsAndEnforcesSymbolKindDiversity() {
    codeAware.getDiversity().setMaxPerSymbol(1);
    CodeAwareDocumentPostProcessor processor =
        new CodeAwareDocumentPostProcessor(codeAware, 3, 2.0, "java");

    Document productionMethod =
        document("src/main/java/App.java", "java", "method run", "method", "public", 0.77, 5, 28, true, false);
    Document testMethod =
        document("src/test/java/AppTest.java", "java", "method shouldRun", "method", "public", 0.8, 5, 40, true, true);
    Document helperClass =
        document("src/main/java/Helper.java", "java", "class Helper", "class", "internal", 0.75, 2, 20, true, false);

    List<Document> reordered =
        processor.process(
            Query.builder().text("explain behavior").build(),
            List.of(testMethod, productionMethod, helperClass));

    assertThat(reordered).containsExactlyInAnyOrder(productionMethod, helperClass, testMethod);
    assertThat(reordered.indexOf(testMethod)).isEqualTo(2);
    assertThat(reordered.indexOf(productionMethod)).isLessThan(reordered.indexOf(testMethod));
  }

  @Test
  void resolvesMissingSymbolKindViaSymbolService() {
    RepoRagSymbolService symbolService = mock(RepoRagSymbolService.class);
    when(symbolService.findSymbolDefinition("repo:demo", "com.demo.Service#doWork"))
        .thenReturn(
            Optional.of(new RepoRagSymbolService.SymbolDefinition(
                "src/main/java/Service.java", 0, "hash-1", "class")));

    CodeAwareDocumentPostProcessor processor =
        new CodeAwareDocumentPostProcessor(
            codeAware, 1, 2.0, "java", symbolService, "repo:demo", true);

    Document missingKind =
        documentWithoutSymbolKind(
            "src/main/java/Service.java",
            "java",
            "com.demo.Service#doWork",
            "public",
            0.72,
            8,
            48,
            false,
            false);
    Document fallback =
        document(
            "scripts/helper.py",
            "python",
            "def helper",
            "function",
            "internal",
            0.8,
            3,
            32,
            false,
            false);

    List<Document> reordered =
        processor.process(Query.builder().text("explain service").build(), List.of(fallback, missingKind));

    assertThat(reordered.get(0)).isEqualTo(missingKind);
  }

  private Document document(
      String path,
      String language,
      String symbol,
      String symbolKind,
      String visibility,
      double score,
      int lineStart,
      int lineEnd,
      boolean documented,
      boolean test) {
    Map<String, Object> metadata = new java.util.LinkedHashMap<>();
    metadata.put("file_path", path);
    metadata.put("language", language);
    metadata.put("parent_symbol", symbol);
    if (symbolKind != null) {
      metadata.put("symbol_kind", symbolKind);
    }
    metadata.put("symbol_visibility", visibility);
    metadata.put("line_start", lineStart);
    metadata.put("line_end", lineEnd);
    metadata.put("chunk_hash", path + ":" + lineStart + ":" + lineEnd);
    metadata.put("docstring", documented ? "Sample documentation" : "");
    metadata.put("is_test", test);
    return Document.builder().id(path + ":" + lineStart).text("// snippet").metadata(metadata).score(score).build();
  }

  private Document documentWithoutSymbolKind(
      String path,
      String language,
      String symbolFqn,
      String visibility,
      double score,
      int lineStart,
      int lineEnd,
      boolean documented,
      boolean test) {
    Map<String, Object> metadata = new java.util.LinkedHashMap<>();
    metadata.put("file_path", path);
    metadata.put("language", language);
    metadata.put("parent_symbol", "");
    metadata.put("symbol_fqn", symbolFqn);
    metadata.put("symbol_visibility", visibility);
    metadata.put("line_start", lineStart);
    metadata.put("line_end", lineEnd);
    metadata.put("chunk_hash", path + ":" + lineStart + ":" + lineEnd);
    metadata.put("docstring", documented ? "Sample documentation" : "");
    metadata.put("is_test", test);
    return Document.builder().id(path + ":" + lineStart).text("// snippet").metadata(metadata).score(score).build();
  }
}
