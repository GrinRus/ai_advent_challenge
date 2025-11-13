package com.aiadvent.mcp.backend.github.rag;

import com.aiadvent.mcp.backend.config.GitHubRagProperties;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class RagParameterGuard {

  private static final Logger log = LoggerFactory.getLogger(RagParameterGuard.class);
  private static final int DEFAULT_TOP_K = 8;
  private static final int MAX_TOP_K = 40;
  private static final double DEFAULT_MIN_SCORE = 0.55d;
  private static final double MIN_SCORE = 0.1d;
  private static final double MAX_SCORE = 0.99d;
  private static final int ABSOLUTE_MAX_NEIGHBOR_LIMIT = 400;

  private final GitHubRagProperties properties;

  public RagParameterGuard(GitHubRagProperties properties) {
    this.properties = Objects.requireNonNull(properties, "properties");
  }

  public GuardResult apply(GitHubRagProperties.ResolvedRagParameterProfile profile) {
    Objects.requireNonNull(profile, "profile");
    List<String> warnings = new ArrayList<>();

    int topK = clampTopK(profile.name(), profile.topK(), warnings);
    int topKPerQuery = clampTopKPerQuery(profile.name(), profile.topKPerQuery(), topK, warnings);
    double minScore = clampScore(profile.name(), profile.minScore(), warnings);
    Map<String, Double> minScoreByLanguage =
        profile.minScoreByLanguage() == null
            ? Map.of()
            : Map.copyOf(profile.minScoreByLanguage());
    Integer rerankTopN = clampRerankTopN(profile.name(), profile.rerankTopN(), warnings);
    boolean codeAwareEnabled =
        profile.codeAwareEnabled() != null
            ? profile.codeAwareEnabled()
            : properties.getRerank().getCodeAware().isEnabled();
    double codeAwareHeadMultiplier =
        clampHeadMultiplier(profile.name(), profile.codeAwareHeadMultiplier(), warnings);

    RepoRagMultiQueryOptions multiQuery =
        buildMultiQuery(profile.name(), profile.multiQuery(), warnings);
    NeighborOptions neighbor = buildNeighbor(profile.name(), profile.neighbor(), warnings);

    ResolvedSearchPlan plan =
        new ResolvedSearchPlan(
            profile.name(),
            topK,
            topKPerQuery,
            minScore,
            minScoreByLanguage,
            rerankTopN,
            codeAwareEnabled,
            codeAwareHeadMultiplier,
            multiQuery,
            neighbor);
    return new GuardResult(plan, warnings.isEmpty() ? List.of() : List.copyOf(warnings));
  }

  private int clampTopK(String profileName, Integer candidate, List<String> warnings) {
    int value = candidate != null ? candidate : DEFAULT_TOP_K;
    if (value < 1) {
      warn(profileName, warnings, "topK", value, 1);
      return 1;
    }
    if (value > MAX_TOP_K) {
      warn(profileName, warnings, "topK", value, MAX_TOP_K);
      logClamp(profileName, "topK", value, MAX_TOP_K, "MAX_TOP_K");
      return MAX_TOP_K;
    }
    return value;
  }

  private int clampTopKPerQuery(
      String profileName, Integer candidate, int fallback, List<String> warnings) {
    int value = candidate != null ? candidate : fallback;
    if (value < 1) {
      warn(profileName, warnings, "topKPerQuery", value, 1);
      return 1;
    }
    if (value > MAX_TOP_K) {
      warn(profileName, warnings, "topKPerQuery", value, MAX_TOP_K);
      logClamp(profileName, "topKPerQuery", value, MAX_TOP_K, "MAX_TOP_K");
      return MAX_TOP_K;
    }
    return value;
  }

  private double clampScore(String profileName, Double candidate, List<String> warnings) {
    double value = candidate != null ? candidate : DEFAULT_MIN_SCORE;
    if (value < MIN_SCORE) {
      warn(profileName, warnings, "minScore", value, MIN_SCORE);
      logClamp(profileName, "minScore", value, MIN_SCORE, "MIN_SCORE");
      return MIN_SCORE;
    }
    if (value > MAX_SCORE) {
      warn(profileName, warnings, "minScore", value, MAX_SCORE);
      logClamp(profileName, "minScore", value, MAX_SCORE, "MAX_SCORE");
      return MAX_SCORE;
    }
    return value;
  }

  private Integer clampRerankTopN(String profileName, Integer candidate, List<String> warnings) {
    if (candidate == null) {
      return properties.getRerank().getTopN();
    }
    if (candidate < 1) {
      warn(profileName, warnings, "rerankTopN", candidate, 1);
      return 1;
    }
    if (candidate > MAX_TOP_K) {
      warn(profileName, warnings, "rerankTopN", candidate, MAX_TOP_K);
      logClamp(profileName, "rerankTopN", candidate, MAX_TOP_K, "MAX_TOP_K");
      return MAX_TOP_K;
    }
    return candidate;
  }

  private double clampHeadMultiplier(
      String profileName, Double candidate, List<String> warnings) {
    double defaultValue =
        Math.max(1.0d, properties.getRerank().getCodeAware().getDefaultHeadMultiplier());
    double value = candidate != null ? candidate : defaultValue;
    if (value < 1.0d) {
      warn(profileName, warnings, "codeAwareHeadMultiplier", value, 1.0d);
      return 1.0d;
    }
    double maxAllowed = Math.max(1.0d, properties.getRerank().getCodeAware().getMaxHeadMultiplier());
    if (value > maxAllowed) {
      warn(profileName, warnings, "codeAwareHeadMultiplier", value, maxAllowed);
      logClamp(profileName, "codeAwareHeadMultiplier", value, maxAllowed, "MAX_HEAD_MULTIPLIER");
      return maxAllowed;
    }
    return value;
  }

  private RepoRagMultiQueryOptions buildMultiQuery(
      String profileName,
      GitHubRagProperties.ResolvedRagParameterProfile.ResolvedMultiQuery source,
      List<String> warnings) {
    boolean enabledDefault = properties.getMultiQuery().isEnabled();
    boolean enabled = source != null && source.enabled() != null ? source.enabled() : enabledDefault;
    int limit = properties.getMultiQuery().getMaxQueries();
    int defaultQueries = properties.getMultiQuery().getDefaultQueries();
    int queries = source != null && source.queries() != null ? source.queries() : defaultQueries;
    int maxQueries = source != null && source.maxQueries() != null ? source.maxQueries() : limit;
    if (queries < 1) {
      warn(profileName, warnings, "multiQuery.queries", queries, 1);
      queries = 1;
    }
    if (queries > limit) {
      warn(profileName, warnings, "multiQuery.queries", queries, limit);
      logClamp(profileName, "multiQuery.queries", queries, limit, "MAX_MULTI_QUERY");
      queries = limit;
    }
    if (maxQueries < 1) {
      warn(profileName, warnings, "multiQuery.maxQueries", maxQueries, 1);
      maxQueries = 1;
    }
    if (maxQueries > limit) {
      warn(profileName, warnings, "multiQuery.maxQueries", maxQueries, limit);
      logClamp(profileName, "multiQuery.maxQueries", maxQueries, limit, "MAX_MULTI_QUERY");
      maxQueries = limit;
    }
    if (queries > maxQueries) {
      warn(profileName, warnings, "multiQuery.queries", queries, maxQueries);
      queries = maxQueries;
    }
    return new RepoRagMultiQueryOptions(enabled, queries, maxQueries);
  }

  private NeighborOptions buildNeighbor(
      String profileName,
      GitHubRagProperties.ResolvedRagParameterProfile.ResolvedNeighbor source,
      List<String> warnings) {
    GitHubRagProperties.Neighbor neighborProps = properties.getPostProcessing().getNeighbor();
    String strategy =
        source != null && StringUtils.hasText(source.strategy())
            ? source.strategy()
            : neighborProps.getStrategy();
    int defaultRadius = neighborProps.getDefaultRadius();
    int maxRadius = neighborProps.getMaxRadius();
    int radius = source != null && source.radius() != null ? source.radius() : defaultRadius;
    if (radius < 0) {
      warn(profileName, warnings, "neighbor.radius", radius, 0);
      radius = 0;
    }
    if (radius > maxRadius) {
      warn(profileName, warnings, "neighbor.radius", radius, maxRadius);
      logClamp(profileName, "neighbor.radius", radius, maxRadius, "MAX_NEIGHBOR_RADIUS");
      radius = maxRadius;
    }
    int defaultLimit = Math.min(neighborProps.getDefaultLimit(), ABSOLUTE_MAX_NEIGHBOR_LIMIT);
    int maxLimit = Math.min(neighborProps.getMaxLimit(), ABSOLUTE_MAX_NEIGHBOR_LIMIT);
    int limit = source != null && source.limit() != null ? source.limit() : defaultLimit;
    if (limit < 0) {
      warn(profileName, warnings, "neighbor.limit", limit, 0);
      limit = 0;
    }
    if (limit > maxLimit) {
      warn(profileName, warnings, "neighbor.limit", limit, maxLimit);
      logClamp(profileName, "neighbor.limit", limit, maxLimit, "MAX_NEIGHBOR_LIMIT");
      limit = maxLimit;
    }
    return new NeighborOptions(strategy != null ? strategy : "OFF", radius, limit);
  }

  private void warn(
      String profileName, List<String> warnings, String field, Object requested, Object applied) {
    if (Objects.equals(requested, applied)) {
      return;
    }
    warnings.add(
        "profile %s: %s=%s → %s".formatted(profileName, field, requested, applied));
  }

  private void logClamp(
      String profileName, String field, Object requested, Object applied, String cause) {
    Map<String, Object> payload = new HashMap<>();
    payload.put("profile", profileName);
    payload.put("field", field);
    payload.put("requested", requested);
    payload.put("applied", applied);
    payload.put("cause", cause);
    log.info("rag_parameter_guard {}", payload);
  }

  public record GuardResult(ResolvedSearchPlan plan, List<String> warnings) {}

  public record ResolvedSearchPlan(
      String profileName,
      int topK,
      int topKPerQuery,
      double minScore,
      Map<String, Double> minScoreByLanguage,
      Integer rerankTopN,
      boolean codeAwareEnabled,
      double codeAwareHeadMultiplier,
      RepoRagMultiQueryOptions multiQuery,
      NeighborOptions neighbor) {}

  public record NeighborOptions(String strategy, int radius, int limit) {}
}
