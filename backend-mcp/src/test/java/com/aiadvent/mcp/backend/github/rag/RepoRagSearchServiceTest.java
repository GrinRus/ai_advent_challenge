package com.aiadvent.mcp.backend.github.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.aiadvent.mcp.backend.config.GitHubRagProperties;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

class RepoRagSearchServiceTest {

  @Mock private VectorStore vectorStore;

  private GitHubRagProperties properties;
  private RepoRagSearchService service;

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);
    properties = new GitHubRagProperties();
    properties.getRerank().setTopN(2);
    properties.getRerank().setScoreWeight(0.4);
    properties.getRerank().setLineSpanWeight(0.6);
    properties.getRerank().setMaxSnippetLines(2);
    RepoRagSearchReranker reranker = new HeuristicRepoRagSearchReranker(properties);
    service = new RepoRagSearchService(vectorStore, properties, reranker);
  }

  @Test
  void reranksTopMatchesByCombinedScore() {
    Document wideSpanHighScore = document("A.java", 0.90, 1, 200);
    Document narrowSpanLowerScore = document("B.java", 0.88, 10, 40);
    Document fallback = document("C.java", 0.50, 5, 50);
    when(vectorStore.similaritySearch(any(SearchRequest.class)))
        .thenReturn(List.of(wideSpanHighScore, narrowSpanLowerScore, fallback));

    RepoRagSearchService.SearchResponse response =
        service.search(new RepoRagSearchService.SearchCommand("owner", "repo", "query", 3, null, null));

    assertThat(response.matches()).hasSize(3);
    assertThat(response.matches().get(0).path()).isEqualTo("B.java"); // better span
    assertThat(response.matches().get(1).path()).isEqualTo("A.java");
    assertThat(response.rerankApplied()).isTrue();
  }

  @Test
  void limitsSnippetLinesAccordingToProperties() {
    Document doc =
        Document.builder()
            .id("doc")
            .text("line1\nline2\nline3\nline4")
            .metadata(defaultMetadata("Snippet.java", 1, 20))
            .score(0.7)
            .build();
    when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(doc));

    RepoRagSearchService.SearchResponse response =
        service.search(new RepoRagSearchService.SearchCommand("owner", "repo", "query", 1, null, null));

    assertThat(response.matches()).hasSize(1);
    long lineCount = response.matches().get(0).snippet().lines().count();
    assertThat(lineCount).isEqualTo(properties.getRerank().getMaxSnippetLines());
  }

  private Document document(String path, double score, int lineStart, int lineEnd) {
    return Document.builder()
        .id(path)
        .text("snippet from " + path + "\nmore context")
        .metadata(defaultMetadata(path, lineStart, lineEnd))
        .score(score)
        .build();
  }

  private Map<String, Object> defaultMetadata(String path, int lineStart, int lineEnd) {
    Map<String, Object> metadata = new HashMap<>();
    metadata.put("file_path", path);
    metadata.put("line_start", lineStart);
    metadata.put("line_end", lineEnd);
    metadata.put("summary", "summary " + path);
    metadata.put("namespace", "repo:owner/" + path.toLowerCase());
    return metadata;
  }
}
