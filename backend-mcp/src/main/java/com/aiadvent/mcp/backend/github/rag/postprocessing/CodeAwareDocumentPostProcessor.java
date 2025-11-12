package com.aiadvent.mcp.backend.github.rag.postprocessing;

import com.aiadvent.mcp.backend.config.GitHubRagProperties;
import com.aiadvent.mcp.backend.config.GitHubRagProperties.Diversity;
import com.aiadvent.mcp.backend.config.GitHubRagProperties.PathPenalty;
import com.aiadvent.mcp.backend.config.GitHubRagProperties.Score;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.postretrieval.document.DocumentPostProcessor;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

/**
 * Applies code-aware reranking by boosting documents that match the requested language,
 * symbol priority and diversity requirements.
 */
public class CodeAwareDocumentPostProcessor implements DocumentPostProcessor {

  private final GitHubRagProperties.CodeAware properties;
  private final int rerankTopN;
  private final double headMultiplier;
  private final String requestedLanguage;

  public CodeAwareDocumentPostProcessor(
      GitHubRagProperties.CodeAware properties,
      int rerankTopN,
      double headMultiplier,
      String requestedLanguage) {
    this.properties = Objects.requireNonNull(properties, "properties");
    this.rerankTopN = Math.max(1, rerankTopN);
    this.headMultiplier = Math.max(1.0d, headMultiplier);
    this.requestedLanguage =
        StringUtils.hasText(requestedLanguage)
            ? requestedLanguage.trim().toLowerCase(Locale.ROOT)
            : null;
  }

  @Override
  public List<Document> process(Query query, List<Document> documents) {
    if (CollectionUtils.isEmpty(documents)) {
      return documents;
    }
    int headSize = computeHeadSize(documents.size());
    if (headSize <= 1) {
      return documents;
    }
    List<Document> head = new ArrayList<>(documents.subList(0, headSize));
    DiversityTracker diversityTracker = new DiversityTracker(properties.getDiversity());
    Map<Document, Double> scoreByDocument = new IdentityHashMap<>();
    for (Document document : head) {
      double combinedScore =
          computeBaseScore(document)
              * languageBonus(document)
              * symbolPriority(document)
              * pathPenalty(document)
              * diversityTracker.weight(document);
      scoreByDocument.put(document, combinedScore);
    }
    head.sort(
        (left, right) ->
            Double.compare(
                scoreByDocument.getOrDefault(right, 0d), scoreByDocument.getOrDefault(left, 0d)));
    List<Document> reordered = new ArrayList<>(documents);
    for (int i = 0; i < head.size(); i++) {
      reordered.set(i, head.get(i));
    }
    return List.copyOf(reordered);
  }

  private int computeHeadSize(int documentCount) {
    int desired = (int) Math.ceil(rerankTopN * headMultiplier);
    return Math.max(1, Math.min(documentCount, desired));
  }

  private double computeBaseScore(Document document) {
    Score scoreConfig = properties.getScore();
    double scoreWeight = clamp(scoreConfig.getWeight(), 0.0, 1.0);
    double spanWeight = clamp(scoreConfig.getSpanWeight(), 0.0, 1.0);
    if (scoreWeight + spanWeight == 0) {
      scoreWeight = 0.7;
      spanWeight = 0.3;
    }
    double similarity = document.getScore() != null ? document.getScore() : 0.0;
    double spanScore = 1.0 / Math.max(1.0, extractLineSpan(document));
    return (scoreWeight * similarity) + (spanWeight * spanScore);
  }

  private double extractLineSpan(Document document) {
    Map<String, Object> metadata = document.getMetadata();
    if (metadata == null || metadata.isEmpty()) {
      return 40.0;
    }
    long start = readLong(metadata.get("line_start"), 1);
    long end = readLong(metadata.get("line_end"), start);
    return Math.max(1, end - start + 1);
  }

  private double languageBonus(Document document) {
    if (!StringUtils.hasText(requestedLanguage)) {
      return 1.0;
    }
    Map<String, Object> metadata = document.getMetadata();
    if (metadata == null) {
      return 1.0;
    }
    String docLanguage = asLowerCase(metadata.get("language"));
    if (!requestedLanguage.equals(docLanguage)) {
      return 1.0;
    }
    Map<String, Double> bonus = properties.getLanguageBonus();
    if (bonus == null || bonus.isEmpty()) {
      return 1.0;
    }
    return Optional.ofNullable(bonus.get(requestedLanguage))
        .or(() -> Optional.ofNullable(bonus.get("*")))
        .filter(value -> value > 0)
        .orElse(1.0);
  }

  private double symbolPriority(Document document) {
    Map<String, Double> priorities = properties.getSymbolPriority();
    if (priorities == null || priorities.isEmpty()) {
      return 1.0;
    }
    Map<String, Object> metadata = document.getMetadata();
    String kind = asLowerCase(metadata != null ? metadata.get("symbol_kind") : null);
    String visibility = asLowerCase(metadata != null ? metadata.get("symbol_visibility") : null);
    if (!StringUtils.hasText(kind)) {
      kind = deriveKindFromParent(metadata);
    }
    if (StringUtils.hasText(kind) && StringUtils.hasText(visibility)) {
      Double combined = priorities.get(kind + "_" + visibility);
      if (combined != null) {
        return combined;
      }
    }
    if (StringUtils.hasText(kind)) {
      Double direct = priorities.get(kind);
      if (direct != null) {
        return direct;
      }
    }
    return priorities.getOrDefault("default", 1.0);
  }

  private String deriveKindFromParent(Map<String, Object> metadata) {
    if (metadata == null) {
      return null;
    }
    String parent =
        Optional.ofNullable(asString(metadata.get("symbol_fqn")))
            .filter(StringUtils::hasText)
            .orElseGet(() -> asString(metadata.get("parent_symbol")));
    if (!StringUtils.hasText(parent)) {
      return null;
    }
    String[] parts = parent.trim().split("\\s+", 2);
    if (parts.length == 0) {
      return null;
    }
    return parts[0].toLowerCase(Locale.ROOT);
  }

  private double pathPenalty(Document document) {
    PathPenalty pathPenalty = properties.getPathPenalty();
    Map<String, Object> metadata = document.getMetadata();
    String path = metadata != null ? asString(metadata.get("file_path")) : null;
    if (!StringUtils.hasText(path)) {
      return 1.0;
    }
    String normalized = path.replace('\\', '/').toLowerCase(Locale.ROOT);
    if (matchesPrefix(normalized, pathPenalty.getAllowPrefixes())) {
      return 1.0;
    }
    if (matchesPrefix(normalized, pathPenalty.getDenyPrefixes())) {
      double penalty = pathPenalty.getPenaltyMultiplier();
      return penalty > 0 ? penalty : 0.2d;
    }
    return 1.0;
  }

  private boolean matchesPrefix(String value, List<String> prefixes) {
    if (prefixes == null || prefixes.isEmpty()) {
      return false;
    }
    for (String prefix : prefixes) {
      if (!StringUtils.hasText(prefix)) {
        continue;
      }
      String normalized = prefix.replace('\\', '/').toLowerCase(Locale.ROOT);
      if (value.startsWith(normalized)) {
        return true;
      }
    }
    return false;
  }

  private String asString(Object value) {
    return value instanceof String str ? str : null;
  }

  private String asLowerCase(Object value) {
    String string = asString(value);
    return StringUtils.hasText(string) ? string.trim().toLowerCase(Locale.ROOT) : null;
  }

  private long readLong(Object value, long defaultValue) {
    if (value instanceof Number number) {
      return number.longValue();
    }
    if (value instanceof String string) {
      try {
        return Long.parseLong(string.trim());
      } catch (NumberFormatException ignore) {
        // ignore invalid values
      }
    }
    return defaultValue;
  }

  private double clamp(double value, double min, double max) {
    return Math.max(min, Math.min(max, value));
  }

  private static final class DiversityTracker {
    private final int maxPerFile;
    private final int maxPerSymbol;
    private final Map<String, Integer> fileCounts = new HashMap<>();
    private final Map<String, Integer> symbolCounts = new HashMap<>();

    DiversityTracker(Diversity diversity) {
      this.maxPerFile = diversity != null ? Math.max(0, diversity.getMaxPerFile()) : 0;
      this.maxPerSymbol = diversity != null ? Math.max(0, diversity.getMaxPerSymbol()) : 0;
    }

    double weight(Document document) {
      double fileWeight = computeWeight(fileCounts, extractFileKey(document), maxPerFile);
      double symbolWeight = computeWeight(symbolCounts, extractSymbolKey(document), maxPerSymbol);
      return fileWeight * symbolWeight;
    }

    private double computeWeight(
        Map<String, Integer> counts, String key, int maxAllowed) {
      if (maxAllowed <= 0 || !StringUtils.hasText(key)) {
        return 1.0;
      }
      int order = counts.merge(key, 1, Integer::sum);
      if (order <= maxAllowed) {
        return 1.0;
      }
      double penalty = (double) maxAllowed / Math.max(order, 1);
      return Math.max(0.1d, penalty);
    }

    private String extractFileKey(Document document) {
      Map<String, Object> metadata = document.getMetadata();
      if (metadata == null) {
        return null;
      }
      String path = (String) metadata.get("file_path");
      if (!StringUtils.hasText(path)) {
        return null;
      }
      return path.replace('\\', '/').toLowerCase(Locale.ROOT);
    }

    private String extractSymbolKey(Document document) {
      Map<String, Object> metadata = document.getMetadata();
      if (metadata == null) {
        return null;
      }
      String symbol =
          Optional.ofNullable((String) metadata.get("symbol_fqn"))
              .filter(StringUtils::hasText)
              .orElseGet(() -> (String) metadata.get("parent_symbol"));
      if (!StringUtils.hasText(symbol)) {
        return null;
      }
      return symbol.trim().toLowerCase(Locale.ROOT);
    }
  }
}
