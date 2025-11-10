package com.aiadvent.mcp.backend.github.rag;

import com.aiadvent.mcp.backend.config.GitHubRagProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpression;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.lang.Nullable;
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

  public RepoRagSearchService(
      @Qualifier("repoRagVectorStore") VectorStore vectorStore,
      GitHubRagProperties properties,
      @Nullable RepoRagSearchReranker reranker) {
    this.vectorStore = Objects.requireNonNull(vectorStore, "vectorStore");
    this.properties = Objects.requireNonNull(properties, "properties");
    this.reranker = reranker;
  }

  public SearchResponse search(SearchCommand command) {
    validate(command);
    int topK = resolveTopK(command.topK());
    double minScore = resolveMinScore(command.minScore());
    String namespace = buildNamespace(command.repoOwner(), command.repoName());

    FilterExpression expression =
        new FilterExpressionBuilder().eq("namespace", namespace).build();

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
    boolean rerankApplied = false;
    if (reranker != null && command.rerankTopN() != null) {
      rerankApplied =
          reranker.rerank(command.query(), matches, Math.min(topK, command.rerankTopN()));
    }

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
    return text.lines().limit(8).collect(Collectors.joining("\n"));
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

  private void validate(SearchCommand command) {
    if (!StringUtils.hasText(command.repoOwner()) || !StringUtils.hasText(command.repoName())) {
      throw new IllegalArgumentException("repoOwner and repoName are required");
    }
    if (!StringUtils.hasText(command.query())) {
      throw new IllegalArgumentException("query must not be blank");
    }
  }

  private String buildNamespace(String owner, String name) {
    return properties.getNamespacePrefix()
        + ":"
        + normalize(owner)
        + "/"
        + normalize(name);
  }

  private String normalize(String value) {
    return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
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
