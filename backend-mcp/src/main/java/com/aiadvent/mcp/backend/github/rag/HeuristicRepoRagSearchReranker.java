package com.aiadvent.mcp.backend.github.rag;

import com.aiadvent.mcp.backend.config.GitHubRagProperties;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.util.CollectionUtils;

public class HeuristicRepoRagSearchReranker implements RepoRagSearchReranker {

  private final GitHubRagProperties properties;

  public HeuristicRepoRagSearchReranker(GitHubRagProperties properties) {
    this.properties = properties;
  }

  @Override
  public boolean rerank(String query, List<RepoRagSearchService.SearchMatch> matches, int topN) {
    if (CollectionUtils.isEmpty(matches) || matches.size() == 1) {
      return false;
    }
    int effectiveTopN = Math.max(1, Math.min(topN, matches.size()));
    List<RepoRagSearchService.SearchMatch> head =
        new ArrayList<>(matches.subList(0, effectiveTopN));
    head.sort(
        Comparator.comparingDouble(this::combinedScore).reversed());
    for (int i = 0; i < head.size(); i++) {
      matches.set(i, head.get(i));
    }
    return true;
  }

  private double combinedScore(RepoRagSearchService.SearchMatch match) {
    double score = Math.max(0.0, match.score());
    double span = extractLineSpan(match.metadata());
    double normalizedSpan = span > 0 ? 1.0 / span : 1.0;
    double scoreWeight = clamp(properties.getRerank().getScoreWeight(), 0.0, 1.0);
    double spanWeight = clamp(properties.getRerank().getLineSpanWeight(), 0.0, 1.0);
    if (scoreWeight + spanWeight == 0) {
      scoreWeight = 0.7;
      spanWeight = 0.3;
    }
    return (scoreWeight * score) + (spanWeight * normalizedSpan);
  }

  private double extractLineSpan(Map<String, Object> metadata) {
    if (metadata == null || metadata.isEmpty()) {
      return 50.0;
    }
    long lineStart = readLong(metadata.get("line_start"), 1);
    long lineEnd = readLong(metadata.get("line_end"), lineStart);
    return Math.max(1, lineEnd - lineStart + 1);
  }

  private long readLong(Object value, long defaultValue) {
    if (value instanceof Number number) {
      return number.longValue();
    }
    if (value instanceof String string && !string.isBlank()) {
      try {
        return Long.parseLong(string.trim());
      } catch (NumberFormatException ignore) {
        // fall through
      }
    }
    return defaultValue;
  }

  private double clamp(double value, double min, double max) {
    return Math.max(min, Math.min(max, value));
  }
}
