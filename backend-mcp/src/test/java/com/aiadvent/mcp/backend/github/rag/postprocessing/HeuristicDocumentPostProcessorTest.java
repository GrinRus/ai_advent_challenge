package com.aiadvent.mcp.backend.github.rag.postprocessing;

import static org.assertj.core.api.Assertions.assertThat;

import com.aiadvent.mcp.backend.config.GitHubRagProperties;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;

class HeuristicDocumentPostProcessorTest {

  @Test
  void respectsOverrideTopN() {
    GitHubRagProperties properties = new GitHubRagProperties();
    properties.getRerank().setTopN(3);

    Document first =
        Document.builder()
            .id("1")
            .text("first")
            .score(0.1)
            .metadata(Map.of("line_start", 1, "line_end", 5))
            .build();
    Document second =
        Document.builder()
            .id("2")
            .text("second")
            .score(0.2)
            .metadata(Map.of("line_start", 6, "line_end", 10))
            .build();
    Document third =
        Document.builder()
            .id("3")
            .text("third")
            .score(0.95)
            .metadata(Map.of("line_start", 11, "line_end", 15))
            .build();
    List<Document> documents = List.of(first, second, third);
    Query query = Query.builder().text("test").build();

    // override = 1 => порядок не меняется
    HeuristicDocumentPostProcessor limited =
        new HeuristicDocumentPostProcessor(properties.getRerank(), 1);
    List<Document> limitedResult = limited.process(query, documents);
    assertThat(limitedResult.get(0).getId()).isEqualTo("1");

    // без override (topN=3) => третий документ может подняться
    HeuristicDocumentPostProcessor defaultProcessor =
        new HeuristicDocumentPostProcessor(properties.getRerank(), null);
    List<Document> defaultResult = defaultProcessor.process(query, documents);
    assertThat(defaultResult.get(0).getId()).isEqualTo("3");
  }
}
