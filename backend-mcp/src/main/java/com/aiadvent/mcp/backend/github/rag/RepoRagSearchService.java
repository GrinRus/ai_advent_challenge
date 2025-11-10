package com.aiadvent.mcp.backend.github.rag;

import com.aiadvent.mcp.backend.config.GitHubRagProperties;
import com.aiadvent.mcp.backend.github.rag.persistence.RepoRagNamespaceStateEntity;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class RepoRagSearchService {

  private static final int DEFAULT_TOP_K = 8;
  private static final int MAX_TOP_K = 40;
  private static final double DEFAULT_MIN_SCORE = 0.55d;

  private final VectorStore vectorStore;
  private final GitHubRagProperties properties;
  private final RepoRagSearchReranker reranker;
  private final RepoRagNamespaceStateService namespaceStateService;

  public RepoRagSearchService(
      @Qualifier("repoRagVectorStore") VectorStore vectorStore,
      GitHubRagProperties properties,
      RepoRagSearchReranker reranker,
      RepoRagNamespaceStateService namespaceStateService) {
    this.vectorStore = Objects.requireNonNull(vectorStore, "vectorStore");
    this.properties = Objects.requireNonNull(properties, "properties");
    this.reranker = Objects.requireNonNull(reranker, "reranker");
    this.namespaceStateService =
        Objects.requireNonNull(namespaceStateService, "namespaceStateService");
  }

  public SearchResponse search(SearchCommand command) {
    validate(command);
    int topK = resolveTopK(command.topK());
    double minScore = resolveMinScore(command.minScore());
    RepoRagNamespaceStateEntity state =
        namespaceStateService
            .findByRepoOwnerAndRepoName(command.repoOwner(), command.repoName())
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Namespace repo:%s/%s has not been indexed yet"
                            .formatted(command.repoOwner(), command.repoName())));
    if (!state.isReady()) {
      throw new IllegalStateException(
          "Namespace repo:%s/%s is still indexing".formatted(command.repoOwner(), command.repoName()));
    }
    String namespace = state.getNamespace();

    var expression = new FilterExpressionBuilder().eq("namespace", namespace).build();

    SearchRequest request =
        SearchRequest.builder()
            .query(command.query().trim())
            .topK(topK)
            .similarityThreshold(minScore)
            .filterExpression(expression)
            .build();

    List<Document> documents = vectorStore.similaritySearch(request);
    if (documents.isEmpty()) {
      return new SearchResponse(List.of(), false);
    }

    List<SearchMatch> matches = toMatches(documents);
    int rerankTopN = resolveRerankTopN(command.rerankTopN());
    boolean rerankApplied = reranker.rerank(command.query(), matches, rerankTopN);

    return new SearchResponse(matches, rerankApplied);
  }

  private List<SearchMatch> toMatches(List<Document> documents) {
    List<SearchMatch> matches = new ArrayList<>();
    for (Document document : documents) {
      Map<String, Object> metadata = document.getMetadata();
      String path = metadata != null ? (String) metadata.getOrDefault("file_path", "") : "";
      String summary = metadata != null ? (String) metadata.getOrDefault("summary", "") : "";
      String snippet = buildSnippet(document.getText());
      double score = document.getScore() != null ? document.getScore() : 0d;
      matches.add(new SearchMatch(path, snippet, summary, score, metadata));
    }
    return matches;
  }

  private String buildSnippet(String text) {
    if (!StringUtils.hasText(text)) {
      return "";
    }
    int maxLines = Math.max(1, properties.getRerank().getMaxSnippetLines());
    return text.lines().limit(maxLines).collect(Collectors.joining("\n"));
  }

  private int resolveTopK(Integer candidate) {
    if (candidate == null) {
      return DEFAULT_TOP_K;
    }
    return Math.max(1, Math.min(MAX_TOP_K, candidate));
  }

  private double resolveMinScore(Double candidate) {
    if (candidate == null) {
      return DEFAULT_MIN_SCORE;
    }
    return Math.max(0.1d, Math.min(0.99d, candidate));
  }

  private int resolveRerankTopN(Integer value) {
    if (value != null && value > 0) {
      return value;
    }
    return Math.max(1, properties.getRerank().getTopN());
  }

  private void validate(SearchCommand command) {
    if (!StringUtils.hasText(command.repoOwner()) || !StringUtils.hasText(command.repoName())) {
      throw new IllegalArgumentException("repoOwner and repoName are required");
    }
    if (!StringUtils.hasText(command.query())) {
      throw new IllegalArgumentException("query must not be blank");
    }
  }

  public record SearchCommand(
      String repoOwner,
      String repoName,
      String query,
      Integer topK,
      Double minScore,
      Integer rerankTopN) {}

  public record SearchResponse(List<SearchMatch> matches, boolean rerankApplied) {}

  public record SearchMatch(
      String path, String snippet, String summary, double score, Map<String, Object> metadata) {}
}
