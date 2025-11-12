package com.aiadvent.mcp.backend.github.rag.postprocessing;

import static org.assertj.core.api.Assertions.assertThat;

import com.aiadvent.mcp.backend.config.GitHubRagProperties;
import java.util.List;
import java.util.Map;
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
        document("src/main/java/App.java", "java", "class App", 0.62, 10, 40);
    Document fallback =
        document("scripts/tool.py", "python", "def handle", 0.9, 5, 18);

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
        document("generated/Main.java", "java", "class Auto", 0.95, 1, 30);
    Document handcrafted =
        document("src/main/java/App.java", "java", "class App", 0.8, 3, 28);

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
        document("src/main/java/App.java", "java", "class App", 0.95, 1, 25);
    Document secondSameFile =
        document("src/main/java/App.java", "java", "method run", 0.92, 30, 60);
    Document differentFile =
        document("src/main/java/Service.java", "java", "class Service", 0.85, 5, 40);

    List<Document> reordered =
        processor.process(
            Query.builder().text("service details").build(),
            List.of(first, secondSameFile, differentFile));

    assertThat(reordered.get(0)).isEqualTo(first);
    assertThat(reordered.get(1)).isEqualTo(differentFile);
  }

  private Document document(
      String path, String language, String symbol, double score, int lineStart, int lineEnd) {
    Map<String, Object> metadata =
        Map.of(
            "file_path", path,
            "language", language,
            "parent_symbol", symbol,
            "line_start", lineStart,
            "line_end", lineEnd,
            "chunk_hash", path + ":" + lineStart + ":" + lineEnd);
    return Document.builder().id(path + ":" + lineStart).text("// snippet").metadata(metadata).score(score).build();
  }
}
