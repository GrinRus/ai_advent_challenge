package com.aiadvent.mcp.backend.github.rag.postprocessing;

import com.aiadvent.mcp.backend.config.GitHubRagProperties;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.postretrieval.document.DocumentPostProcessor;
import org.springframework.util.CollectionUtils;

/**
 * Reorders the head of the document list using the historic heuristic (score + span).
 */
public class HeuristicDocumentPostProcessor implements DocumentPostProcessor {

  private final GitHubRagProperties.Rerank rerank;

  public HeuristicDocumentPostProcessor(GitHubRagProperties.Rerank rerank) {
    this.rerank = rerank;
  }

  @Override
  public List<Document> process(Query query, List<Document> documents) {
    if (CollectionUtils.isEmpty(documents) || documents.size() == 1) {
      return documents;
    }
    int headSize = Math.max(1, Math.min(rerank.getTopN(), documents.size()));
    List<Document> head = new ArrayList<>(documents.subList(0, headSize));
    head.sort(Comparator.comparingDouble(this::combinedScore).reversed());
    List<Document> result = new ArrayList<>(documents);
    for (int i = 0; i < head.size(); i++) {
      result.set(i, head.get(i));
    }
    return List.copyOf(result);
  }

  private double combinedScore(Document document) {
    double score = document.getScore() != null ? document.getScore() : 0.0;
    double spanScore = 1.0 / Math.max(1.0, extractLineSpan(document.getMetadata()));
    double scoreWeight = clamp(rerank.getScoreWeight(), 0.0, 1.0);
    double spanWeight = clamp(rerank.getLineSpanWeight(), 0.0, 1.0);
    if (scoreWeight + spanWeight == 0) {
      scoreWeight = 0.7;
      spanWeight = 0.3;
    }
    return (scoreWeight * score) + (spanWeight * spanScore);
  }

  private double extractLineSpan(Map<String, Object> metadata) {
    if (metadata == null || metadata.isEmpty()) {
      return 50.0;
    }
    long start = readLong(metadata.get("line_start"), 1);
    long end = readLong(metadata.get("line_end"), start);
    return Math.max(1, end - start + 1);
  }

  private long readLong(Object value, long defaultValue) {
    if (value instanceof Number number) {
      return number.longValue();
    }
    if (value instanceof String string) {
      try {
        return Long.parseLong(string.trim());
      } catch (NumberFormatException ignore) {
        // ignore
      }
    }
    return defaultValue;
  }

  private double clamp(double value, double min, double max) {
    return Math.max(min, Math.min(max, value));
  }
}

