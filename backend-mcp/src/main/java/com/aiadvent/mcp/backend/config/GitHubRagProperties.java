package com.aiadvent.mcp.backend.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

@ConfigurationProperties(prefix = "github.rag")
public class GitHubRagProperties implements InitializingBean {

  private static final int MAX_TOP_K = 40;
  private static final double MIN_SCORE = 0.1d;
  private static final double MAX_SCORE = 0.99d;
  private static final Set<String> SUPPORTED_NEIGHBOR_STRATEGIES =
      Set.of("OFF", "LINEAR", "PARENT_SYMBOL", "CALL_GRAPH");

  private String namespacePrefix = "repo";
  private int maxConcurrency = 2;
  private final Chunking chunking = new Chunking();
  private final Retry retry = new Retry();
  private final Ignore ignore = new Ignore();
  private final Embedding embedding = new Embedding();
  private final Rerank rerank = new Rerank();
  private final QueryTransformers queryTransformers = new QueryTransformers();
  private final MultiQuery multiQuery = new MultiQuery();
  private final PostProcessing postProcessing = new PostProcessing();
  private final Generation generation = new Generation();
  private List<RagParameterProfile> parameterProfiles = new ArrayList<>();
  private String defaultProfile;
  private Map<String, ResolvedRagParameterProfile> profileIndex = Map.of();
  private String resolvedDefaultProfile;

  @Override
  public void afterPropertiesSet() {
    initializeProfiles();
  }

  public String getNamespacePrefix() {
    return namespacePrefix;
  }

  public void setNamespacePrefix(String namespacePrefix) {
    this.namespacePrefix = namespacePrefix;
  }

  public int getMaxConcurrency() {
    return maxConcurrency;
  }

  public void setMaxConcurrency(int maxConcurrency) {
    this.maxConcurrency = maxConcurrency;
  }

  public Chunking getChunking() {
    return chunking;
  }

  public Retry getRetry() {
    return retry;
  }

  public Ignore getIgnore() {
    return ignore;
  }

  public Embedding getEmbedding() {
    return embedding;
  }

  public Rerank getRerank() {
    return rerank;
  }

  public QueryTransformers getQueryTransformers() {
    return queryTransformers;
  }

  public MultiQuery getMultiQuery() {
    return multiQuery;
  }

  public PostProcessing getPostProcessing() {
    return postProcessing;
  }

  public Generation getGeneration() {
    return generation;
  }

  private void initializeProfiles() {
    if (parameterProfiles == null || parameterProfiles.isEmpty()) {
      profileIndex = Map.of();
      resolvedDefaultProfile = null;
      return;
    }
    Map<String, ResolvedRagParameterProfile> index = new LinkedHashMap<>();
    for (RagParameterProfile raw : parameterProfiles) {
      ResolvedRagParameterProfile resolved = sanitizeProfile(raw);
      String key = normalizeProfileName(resolved.name());
      if (index.putIfAbsent(key, resolved) != null) {
        throw new IllegalStateException(
            "Duplicate RAG profile name: " + resolved.name());
      }
    }
    profileIndex = Collections.unmodifiableMap(index);
    resolvedDefaultProfile = determineDefaultProfile(index);
  }

  private String determineDefaultProfile(Map<String, ResolvedRagParameterProfile> index) {
    if (index.isEmpty()) {
      return null;
    }
    if (!StringUtils.hasText(defaultProfile)) {
      return index.keySet().iterator().next();
    }
    String candidate = normalizeProfileName(defaultProfile);
    if (!index.containsKey(candidate)) {
      throw new IllegalStateException(
          "Configured defaultProfile '%s' is not present in github.rag.parameterProfiles"
              .formatted(defaultProfile));
    }
    return candidate;
  }

  private ResolvedRagParameterProfile sanitizeProfile(RagParameterProfile raw) {
    Objects.requireNonNull(raw, "profile");
    String name = raw.getName();
    if (!StringUtils.hasText(name)) {
      throw new IllegalStateException("RAG profile name must not be blank");
    }
    Integer topK = validateTopK(raw.getTopK(), "topK", name);
    Integer topKPerQuery = validateTopK(raw.getTopKPerQuery(), "topKPerQuery", name);
    Double minScore = validateScore(raw.getMinScore(), "minScore", name);
    Double minScoreFallback = validateScore(raw.getMinScoreFallback(), "minScoreFallback", name);
    String minScoreClassifier = normalizeClassifier(raw.getMinScoreClassifier());
    List<String> overviewBoostKeywords = sanitizeOverviewKeywords(raw.getOverviewBoostKeywords());
    Map<String, Double> minScoreByLanguage = sanitizeLanguageThresholds(raw.getMinScoreByLanguage(), name);
    Integer rerankTopN = validateTopK(raw.getRerankTopN(), "rerankTopN", name);
    Boolean codeAwareEnabled = raw.getCodeAwareEnabled();
    Double codeAwareHeadMultiplier = validateHeadMultiplier(raw.getCodeAwareHeadMultiplier(), name);
    ResolvedRagParameterProfile.ResolvedNeighbor neighbor = sanitizeNeighbor(raw.getNeighbor(), name);
    ResolvedRagParameterProfile.ResolvedMultiQuery multiQuery =
        sanitizeMultiQuery(raw.getMultiQuery(), name);
    return new ResolvedRagParameterProfile(
        name.trim(),
        topK,
        topKPerQuery,
        minScore,
        minScoreByLanguage,
        rerankTopN,
        codeAwareEnabled,
        codeAwareHeadMultiplier,
        multiQuery,
        neighbor,
        minScoreFallback,
        minScoreClassifier,
        overviewBoostKeywords);
  }

  private Integer validateTopK(Integer candidate, String field, String profileName) {
    if (candidate == null) {
      return null;
    }
    if (candidate < 1 || candidate > MAX_TOP_K) {
      throw new IllegalStateException(
          "%s for profile '%s' must be between 1 and %d"
              .formatted(field, profileName, MAX_TOP_K));
    }
    return candidate;
  }

  private Double validateScore(Double candidate, String field, String profileName) {
    if (candidate == null) {
      return null;
    }
    if (candidate < MIN_SCORE || candidate > MAX_SCORE) {
      throw new IllegalStateException(
          "%s for profile '%s' must be between %.2f and %.2f"
              .formatted(field, profileName, MIN_SCORE, MAX_SCORE));
    }
    return candidate;
  }

  private Double validateHeadMultiplier(Double candidate, String profileName) {
    if (candidate == null) {
      return null;
    }
    double maxAllowed = rerank.getCodeAware().getMaxHeadMultiplier();
    if (candidate < 1.0d || candidate > maxAllowed) {
      throw new IllegalStateException(
          "codeAwareHeadMultiplier for profile '%s' must be between 1.0 and %s"
              .formatted(profileName, maxAllowed));
    }
    return candidate;
  }

  private Map<String, Double> sanitizeLanguageThresholds(
      Map<String, Double> source, String profileName) {
    if (source == null || source.isEmpty()) {
      return Map.of();
    }
    Map<String, Double> sanitized = new LinkedHashMap<>();
    source.forEach(
        (language, threshold) -> {
          if (!StringUtils.hasText(language) || threshold == null) {
            return;
          }
          if (threshold < MIN_SCORE || threshold > MAX_SCORE) {
            throw new IllegalStateException(
                "minScoreByLanguage[%s] for profile '%s' must be between %.2f and %.2f"
                    .formatted(language, profileName, MIN_SCORE, MAX_SCORE));
          }
          sanitized.put(language.toLowerCase(Locale.ROOT), threshold);
        });
    return Collections.unmodifiableMap(sanitized);
  }

  private List<String> sanitizeOverviewKeywords(List<String> source) {
    if (source == null || source.isEmpty()) {
      return List.of();
    }
    List<String> normalized = new ArrayList<>();
    for (String keyword : source) {
      if (!StringUtils.hasText(keyword)) {
        continue;
      }
      String token = keyword.trim().toLowerCase(Locale.ROOT);
      if (!normalized.contains(token)) {
        normalized.add(token);
      }
    }
    return List.copyOf(normalized);
  }

  private String normalizeClassifier(String candidate) {
    if (!StringUtils.hasText(candidate)) {
      return null;
    }
    String value = candidate.trim().toLowerCase(Locale.ROOT);
    if (!Set.of("overview").contains(value)) {
      throw new IllegalStateException(
          "Unsupported minScoreClassifier '%s'. Allowed values: overview".formatted(candidate));
    }
    return value;
  }

  private ResolvedRagParameterProfile.ResolvedNeighbor sanitizeNeighbor(
      RagParameterProfile.ProfileNeighbor neighbor, String profileName) {
    if (neighbor == null) {
      return new ResolvedRagParameterProfile.ResolvedNeighbor(null, null, null);
    }
    String strategy = neighbor.getStrategy();
    if (StringUtils.hasText(strategy)) {
      String candidate = strategy.trim().toUpperCase(Locale.ROOT);
      if ("FALSE".equals(candidate)) {
        candidate = "OFF";
      }
      if (!SUPPORTED_NEIGHBOR_STRATEGIES.contains(candidate)) {
        throw new IllegalStateException(
            "neighbor.strategy for profile '%s' must be one of %s (got '%s')"
                .formatted(profileName, SUPPORTED_NEIGHBOR_STRATEGIES, candidate));
      }
      strategy = candidate;
    } else {
      strategy = null;
    }
    Integer radius = neighbor.getRadius();
    if (radius != null) {
      if (radius < 0 || radius > postProcessing.getNeighbor().getMaxRadius()) {
        throw new IllegalStateException(
            "neighbor.radius for profile '%s' must be between 0 and %d"
                .formatted(profileName, postProcessing.getNeighbor().getMaxRadius()));
      }
    }
    Integer limit = neighbor.getLimit();
    if (limit != null) {
      if (limit < 0 || limit > postProcessing.getNeighbor().getMaxLimit()) {
        throw new IllegalStateException(
            "neighbor.limit for profile '%s' must be between 0 and %d"
                .formatted(profileName, postProcessing.getNeighbor().getMaxLimit()));
      }
    }
    return new ResolvedRagParameterProfile.ResolvedNeighbor(strategy, radius, limit);
  }

  private ResolvedRagParameterProfile.ResolvedMultiQuery sanitizeMultiQuery(
      RagParameterProfile.ProfileMultiQuery multiQuery, String profileName) {
    if (multiQuery == null) {
      return new ResolvedRagParameterProfile.ResolvedMultiQuery(null, null, null);
    }
    Integer queries = multiQuery.getQueries();
    if (queries != null) {
      if (queries < 1 || queries > this.multiQuery.getMaxQueries()) {
        throw new IllegalStateException(
            "multiQuery.queries for profile '%s' must be between 1 and %d"
                .formatted(profileName, this.multiQuery.getMaxQueries()));
      }
    }
    Integer maxQueries = multiQuery.getMaxQueries();
    if (maxQueries != null) {
      if (maxQueries < 1 || maxQueries > this.multiQuery.getMaxQueries()) {
        throw new IllegalStateException(
            "multiQuery.maxQueries for profile '%s' must be between 1 and %d"
                .formatted(profileName, this.multiQuery.getMaxQueries()));
      }
    }
    return new ResolvedRagParameterProfile.ResolvedMultiQuery(
        multiQuery.getEnabled(), queries, maxQueries);
  }

  private String normalizeProfileName(String name) {
    return name.trim().toLowerCase(Locale.ROOT);
  }

  public List<RagParameterProfile> getParameterProfiles() {
    return parameterProfiles;
  }

  public void setParameterProfiles(List<RagParameterProfile> parameterProfiles) {
    this.parameterProfiles = parameterProfiles != null ? parameterProfiles : new ArrayList<>();
  }

  public String getDefaultProfile() {
    return defaultProfile;
  }

  public void setDefaultProfile(String defaultProfile) {
    this.defaultProfile = defaultProfile;
  }

  public Map<String, ResolvedRagParameterProfile> getProfileIndex() {
    return profileIndex;
  }

  public boolean hasProfiles() {
    return !profileIndex.isEmpty();
  }

  public String getResolvedDefaultProfile() {
    return resolvedDefaultProfile;
  }

  public ResolvedRagParameterProfile resolveProfile(String name) {
    if (profileIndex.isEmpty()) {
      throw new IllegalStateException("github.rag.parameterProfiles must be configured");
    }
    String key = StringUtils.hasText(name) ? normalizeProfileName(name) : resolvedDefaultProfile;
    ResolvedRagParameterProfile profile = profileIndex.get(key);
    if (profile == null) {
      throw new IllegalStateException("Unknown RAG parameter profile: " + name);
    }
    return profile;
  }

  public static class RagParameterProfile {
    private String name;
    private Integer topK;
    private Integer topKPerQuery;
    private Double minScore;
    private Map<String, Double> minScoreByLanguage = new HashMap<>();
    private Double minScoreFallback;
    private String minScoreClassifier;
    private List<String> overviewBoostKeywords = new ArrayList<>();
    private Integer rerankTopN;
    private Boolean codeAwareEnabled;
    private Double codeAwareHeadMultiplier;
    private final ProfileMultiQuery multiQuery = new ProfileMultiQuery();
    private final ProfileNeighbor neighbor = new ProfileNeighbor();

    public String getName() {
      return name;
    }

    public void setName(String name) {
      this.name = name;
    }

    public Integer getTopK() {
      return topK;
    }

    public void setTopK(Integer topK) {
      this.topK = topK;
    }

    public Integer getTopKPerQuery() {
      return topKPerQuery;
    }

    public void setTopKPerQuery(Integer topKPerQuery) {
      this.topKPerQuery = topKPerQuery;
    }

    public Double getMinScore() {
      return minScore;
    }

    public void setMinScore(Double minScore) {
      this.minScore = minScore;
    }

    public Double getMinScoreFallback() {
      return minScoreFallback;
    }

    public void setMinScoreFallback(Double minScoreFallback) {
      this.minScoreFallback = minScoreFallback;
    }

    public String getMinScoreClassifier() {
      return minScoreClassifier;
    }

    public void setMinScoreClassifier(String minScoreClassifier) {
      this.minScoreClassifier = minScoreClassifier;
    }

    public List<String> getOverviewBoostKeywords() {
      return overviewBoostKeywords;
    }

    public void setOverviewBoostKeywords(List<String> overviewBoostKeywords) {
      this.overviewBoostKeywords =
          overviewBoostKeywords != null ? new ArrayList<>(overviewBoostKeywords) : new ArrayList<>();
    }

    public Map<String, Double> getMinScoreByLanguage() {
      return minScoreByLanguage;
    }

    public void setMinScoreByLanguage(Map<String, Double> minScoreByLanguage) {
      this.minScoreByLanguage =
          minScoreByLanguage != null ? new HashMap<>(minScoreByLanguage) : new HashMap<>();
    }

    public Integer getRerankTopN() {
      return rerankTopN;
    }

    public void setRerankTopN(Integer rerankTopN) {
      this.rerankTopN = rerankTopN;
    }

    public Boolean getCodeAwareEnabled() {
      return codeAwareEnabled;
    }

    public void setCodeAwareEnabled(Boolean codeAwareEnabled) {
      this.codeAwareEnabled = codeAwareEnabled;
    }

    public Double getCodeAwareHeadMultiplier() {
      return codeAwareHeadMultiplier;
    }

    public void setCodeAwareHeadMultiplier(Double codeAwareHeadMultiplier) {
      this.codeAwareHeadMultiplier = codeAwareHeadMultiplier;
    }

    public ProfileMultiQuery getMultiQuery() {
      return multiQuery;
    }

    public ProfileNeighbor getNeighbor() {
      return neighbor;
    }

    public static class ProfileMultiQuery {
      private Boolean enabled;
      private Integer queries;
      private Integer maxQueries;

      public Boolean getEnabled() {
        return enabled;
      }

      public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
      }

      public Integer getQueries() {
        return queries;
      }

      public void setQueries(Integer queries) {
        this.queries = queries;
      }

      public Integer getMaxQueries() {
        return maxQueries;
      }

      public void setMaxQueries(Integer maxQueries) {
        this.maxQueries = maxQueries;
      }
    }

    public static class ProfileNeighbor {
      private String strategy;
      private Integer radius;
      private Integer limit;

      public String getStrategy() {
        return strategy;
      }

      public void setStrategy(String strategy) {
        this.strategy = strategy;
      }

      public Integer getRadius() {
        return radius;
      }

      public void setRadius(Integer radius) {
        this.radius = radius;
      }

      public Integer getLimit() {
        return limit;
      }

      public void setLimit(Integer limit) {
        this.limit = limit;
      }
    }
  }

  public record ResolvedRagParameterProfile(
      String name,
      Integer topK,
      Integer topKPerQuery,
      Double minScore,
      Map<String, Double> minScoreByLanguage,
      Integer rerankTopN,
      Boolean codeAwareEnabled,
      Double codeAwareHeadMultiplier,
      ResolvedMultiQuery multiQuery,
      ResolvedNeighbor neighbor,
      Double minScoreFallback,
      String minScoreClassifier,
      List<String> overviewBoostKeywords) {

    public record ResolvedMultiQuery(Boolean enabled, Integer queries, Integer maxQueries) {}

    public record ResolvedNeighbor(String strategy, Integer radius, Integer limit) {}
  }

  public static class Chunking {
    private Strategy strategy = Strategy.LINE;
    private int overlapLines = 20;
    private int overlapTokens = 120;
    private final Line line = new Line();
    private final ByteStrategy byteStrategy = new ByteStrategy();
    private final Token token = new Token();
    private final Semantic semantic = new Semantic();

    public Strategy getStrategy() {
      return strategy;
    }

    public void setStrategy(Strategy strategy) {
      this.strategy = strategy;
    }

    public int getOverlapLines() {
      return overlapLines;
    }

    public void setOverlapLines(int overlapLines) {
      this.overlapLines = overlapLines;
    }

    public int getOverlapTokens() {
      return overlapTokens;
    }

    public void setOverlapTokens(int overlapTokens) {
      this.overlapTokens = overlapTokens;
    }

    public Line getLine() {
      return line;
    }

    public ByteStrategy getByteStrategy() {
      return byteStrategy;
    }

    public Token getToken() {
      return token;
    }

    public Semantic getSemantic() {
      return semantic;
    }
  }

  public enum Strategy {
    LINE,
    BYTE,
    TOKEN,
    SEMANTIC
  }

  public static class Line {
    private int maxLines = 160;
    private int maxBytes = 2048;

    public int getMaxLines() {
      return maxLines;
    }

    public void setMaxLines(int maxLines) {
      this.maxLines = maxLines;
    }

    public int getMaxBytes() {
      return maxBytes;
    }

    public void setMaxBytes(int maxBytes) {
      this.maxBytes = maxBytes;
    }
  }

  public static class ByteStrategy {
    private int maxBytes = 4096;

    public int getMaxBytes() {
      return maxBytes;
    }

    public void setMaxBytes(int maxBytes) {
      this.maxBytes = maxBytes;
    }
  }

  public static class Token {
    private int chunkSizeTokens = 800;
    private int minChunkChars = 200;
    private int minChunkLengthToEmbed = 40;
    private int maxNumChunks = 10000;

    public int getChunkSizeTokens() {
      return chunkSizeTokens;
    }

    public void setChunkSizeTokens(int chunkSizeTokens) {
      this.chunkSizeTokens = chunkSizeTokens;
    }

    public int getMinChunkChars() {
      return minChunkChars;
    }

    public void setMinChunkChars(int minChunkChars) {
      this.minChunkChars = minChunkChars;
    }

    public int getMinChunkLengthToEmbed() {
      return minChunkLengthToEmbed;
    }

    public void setMinChunkLengthToEmbed(int minChunkLengthToEmbed) {
      this.minChunkLengthToEmbed = minChunkLengthToEmbed;
    }

    public int getMaxNumChunks() {
      return maxNumChunks;
    }

    public void setMaxNumChunks(int maxNumChunks) {
      this.maxNumChunks = maxNumChunks;
    }
  }

  public static class Semantic {
    private boolean enabled = false;
    private String splitterClass =
        "org.springframework.ai.transformer.splitter.SemanticTextSplitter";
    private int chunkSizeTokens = 1024;

    public boolean isEnabled() {
      return enabled;
    }

    public void setEnabled(boolean enabled) {
      this.enabled = enabled;
    }

    public String getSplitterClass() {
      return splitterClass;
    }

    public void setSplitterClass(String splitterClass) {
      this.splitterClass = splitterClass;
    }

    public int getChunkSizeTokens() {
      return chunkSizeTokens;
    }

    public void setChunkSizeTokens(int chunkSizeTokens) {
      this.chunkSizeTokens = chunkSizeTokens;
    }
  }

  public static class Retry {
    private int maxAttempts = 5;
    private Duration initialBackoff = Duration.ofSeconds(15);

    public int getMaxAttempts() {
      return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
      this.maxAttempts = maxAttempts;
    }

    public Duration getInitialBackoff() {
      return initialBackoff;
    }

    public void setInitialBackoff(Duration initialBackoff) {
      this.initialBackoff = initialBackoff;
    }
  }

  public static class Ignore {
    private List<String> directories =
        new ArrayList<>(List.of(".git", ".github", "node_modules", "dist", "build"));

    public List<String> getDirectories() {
      return directories;
    }

    public void setDirectories(List<String> directories) {
      this.directories = directories;
    }
  }

  public static class Embedding {
    private String model = "text-embedding-3-small";
    private int dimensions = 1536;

    public String getModel() {
      return model;
    }

    public void setModel(String model) {
      this.model = model;
    }

    public int getDimensions() {
      return dimensions;
    }

    public void setDimensions(int dimensions) {
      this.dimensions = dimensions;
    }
  }

  public static class Rerank {
    private int topN = 8;
    private double scoreWeight = 0.8;
    private double lineSpanWeight = 0.2;
    private int maxSnippetLines = 8;
    private final CodeAware codeAware = new CodeAware();

    public int getTopN() {
      return topN;
    }

    public void setTopN(int topN) {
      this.topN = topN;
    }

    public double getScoreWeight() {
      return scoreWeight;
    }

    public void setScoreWeight(double scoreWeight) {
      this.scoreWeight = scoreWeight;
    }

    public double getLineSpanWeight() {
      return lineSpanWeight;
    }

    public void setLineSpanWeight(double lineSpanWeight) {
      this.lineSpanWeight = lineSpanWeight;
    }

    public int getMaxSnippetLines() {
      return maxSnippetLines;
    }

    public void setMaxSnippetLines(int maxSnippetLines) {
      this.maxSnippetLines = maxSnippetLines;
    }

    public CodeAware getCodeAware() {
      return codeAware;
    }
  }

  public static class CodeAware {
    private boolean enabled = true;
    private double defaultHeadMultiplier = 2.0;
    private double maxHeadMultiplier = 4.0;
    private final Map<String, Double> languageBonus = new HashMap<>();
    private final Map<String, Double> symbolPriority = new HashMap<>();
    private final PathPenalty pathPenalty = new PathPenalty();
    private final Diversity diversity = new Diversity();
    private final Score score = new Score();

    public boolean isEnabled() {
      return enabled;
    }

    public void setEnabled(boolean enabled) {
      this.enabled = enabled;
    }

    public double getDefaultHeadMultiplier() {
      return defaultHeadMultiplier;
    }

    public void setDefaultHeadMultiplier(double defaultHeadMultiplier) {
      this.defaultHeadMultiplier = defaultHeadMultiplier;
    }

    public double getMaxHeadMultiplier() {
      return maxHeadMultiplier;
    }

    public void setMaxHeadMultiplier(double maxHeadMultiplier) {
      this.maxHeadMultiplier = maxHeadMultiplier;
    }

    public Map<String, Double> getLanguageBonus() {
      return languageBonus;
    }

    public Map<String, Double> getSymbolPriority() {
      return symbolPriority;
    }

    public PathPenalty getPathPenalty() {
      return pathPenalty;
    }

    public Diversity getDiversity() {
      return diversity;
    }

    public Score getScore() {
      return score;
    }
  }

  public static class PathPenalty {
    private List<String> denyPrefixes = new ArrayList<>();
    private List<String> allowPrefixes = new ArrayList<>();
    private double penaltyMultiplier = 0.6;

    public List<String> getDenyPrefixes() {
      return denyPrefixes;
    }

    public void setDenyPrefixes(List<String> denyPrefixes) {
      this.denyPrefixes = denyPrefixes;
    }

    public List<String> getAllowPrefixes() {
      return allowPrefixes;
    }

    public void setAllowPrefixes(List<String> allowPrefixes) {
      this.allowPrefixes = allowPrefixes;
    }

    public double getPenaltyMultiplier() {
      return penaltyMultiplier;
    }

    public void setPenaltyMultiplier(double penaltyMultiplier) {
      this.penaltyMultiplier = penaltyMultiplier;
    }
  }

  public static class Diversity {
    private int maxPerFile = 3;
    private int maxPerSymbol = 2;

    public int getMaxPerFile() {
      return maxPerFile;
    }

    public void setMaxPerFile(int maxPerFile) {
      this.maxPerFile = maxPerFile;
    }

    public int getMaxPerSymbol() {
      return maxPerSymbol;
    }

    public void setMaxPerSymbol(int maxPerSymbol) {
      this.maxPerSymbol = maxPerSymbol;
    }
  }

  public static class Score {
    private double weight = 0.75;
    private double spanWeight = 0.25;

    public double getWeight() {
      return weight;
    }

    public void setWeight(double weight) {
      this.weight = weight;
    }

    public double getSpanWeight() {
      return spanWeight;
    }

    public void setSpanWeight(double spanWeight) {
      this.spanWeight = spanWeight;
    }
  }

  public static class QueryTransformers {
    private boolean enabled = true;
    private int maxHistoryTokens = 1600;
    private String defaultTargetLanguage = "ru";
    private String model = "gpt-4o-mini";
    private double temperature = 0.0;

    public boolean isEnabled() {
      return enabled;
    }

    public void setEnabled(boolean enabled) {
      this.enabled = enabled;
    }

    public int getMaxHistoryTokens() {
      return maxHistoryTokens;
    }

    public void setMaxHistoryTokens(int maxHistoryTokens) {
      this.maxHistoryTokens = maxHistoryTokens;
    }

    public String getDefaultTargetLanguage() {
      return defaultTargetLanguage;
    }

    public void setDefaultTargetLanguage(String defaultTargetLanguage) {
      this.defaultTargetLanguage = defaultTargetLanguage;
    }

    public String getModel() {
      return model;
    }

    public void setModel(String model) {
      this.model = model;
    }

    public double getTemperature() {
      return temperature;
    }

    public void setTemperature(double temperature) {
      this.temperature = temperature;
    }
  }

  public static class MultiQuery {
    private boolean enabled = true;
    private int defaultQueries = 3;
    private int maxQueries = 6;

    public boolean isEnabled() {
      return enabled;
    }

    public void setEnabled(boolean enabled) {
      this.enabled = enabled;
    }

    public int getDefaultQueries() {
      return defaultQueries;
    }

    public void setDefaultQueries(int defaultQueries) {
      this.defaultQueries = defaultQueries;
    }

    public int getMaxQueries() {
      return maxQueries;
    }

    public void setMaxQueries(int maxQueries) {
      this.maxQueries = maxQueries;
    }
  }

  public static class PostProcessing {
    private int maxContextTokens = 4000;
    private boolean llmCompressionEnabled = true;
    private String llmCompressionModel = "gpt-4o-mini";
    private double llmCompressionTemperature = 0.0;
    private final Neighbor neighbor = new Neighbor();

    public int getMaxContextTokens() {
      return maxContextTokens;
    }

    public void setMaxContextTokens(int maxContextTokens) {
      this.maxContextTokens = maxContextTokens;
    }

    public boolean isLlmCompressionEnabled() {
      return llmCompressionEnabled;
    }

    public void setLlmCompressionEnabled(boolean llmCompressionEnabled) {
      this.llmCompressionEnabled = llmCompressionEnabled;
    }

    public String getLlmCompressionModel() {
      return llmCompressionModel;
    }

    public void setLlmCompressionModel(String llmCompressionModel) {
      this.llmCompressionModel = llmCompressionModel;
    }

    public double getLlmCompressionTemperature() {
      return llmCompressionTemperature;
    }

    public void setLlmCompressionTemperature(double llmCompressionTemperature) {
      this.llmCompressionTemperature = llmCompressionTemperature;
    }

    public Neighbor getNeighbor() {
      return neighbor;
    }
  }

  public static class Neighbor {
    private boolean enabled = true;
    private int defaultRadius = 1;
    private int defaultLimit = 6;
    private int maxRadius = 5;
    private int maxLimit = 12;
    private String strategy = "LINEAR";

    public boolean isEnabled() {
      return enabled;
    }

    public void setEnabled(boolean enabled) {
      this.enabled = enabled;
    }

    public int getDefaultRadius() {
      return defaultRadius;
    }

    public void setDefaultRadius(int defaultRadius) {
      this.defaultRadius = defaultRadius;
    }

    public int getDefaultLimit() {
      return defaultLimit;
    }

    public void setDefaultLimit(int defaultLimit) {
      this.defaultLimit = defaultLimit;
    }

    public int getMaxRadius() {
      return maxRadius;
    }

    public void setMaxRadius(int maxRadius) {
      this.maxRadius = maxRadius;
    }

    public int getMaxLimit() {
      return maxLimit;
    }

    public void setMaxLimit(int maxLimit) {
      this.maxLimit = maxLimit;
    }

    public String getStrategy() {
      return strategy;
    }

    public void setStrategy(String strategy) {
      this.strategy = strategy;
    }
  }

  public static class Generation {
    private boolean allowEmptyContext = true;
    private String promptTemplate = "classpath:prompts/github-rag-context.st";
    private String emptyContextTemplate = "classpath:prompts/github-rag-empty-context.st";
    private String summaryTemplate = "classpath:prompts/github-rag-summary.st";
    private String noResultsReason = "CONTEXT_NOT_FOUND";
    private String emptyContextMessage = "Индекс не содержит подходящих документов";

    public boolean isAllowEmptyContext() {
      return allowEmptyContext;
    }

    public void setAllowEmptyContext(boolean allowEmptyContext) {
      this.allowEmptyContext = allowEmptyContext;
    }

    public String getPromptTemplate() {
      return promptTemplate;
    }

    public void setPromptTemplate(String promptTemplate) {
      this.promptTemplate = promptTemplate;
    }

    public String getEmptyContextTemplate() {
      return emptyContextTemplate;
    }

    public void setEmptyContextTemplate(String emptyContextTemplate) {
      this.emptyContextTemplate = emptyContextTemplate;
    }

    public String getSummaryTemplate() {
      return summaryTemplate;
    }

    public void setSummaryTemplate(String summaryTemplate) {
      this.summaryTemplate = summaryTemplate;
    }

    public String getNoResultsReason() {
      return noResultsReason;
    }

    public void setNoResultsReason(String noResultsReason) {
      this.noResultsReason = noResultsReason;
    }

    public String getEmptyContextMessage() {
      return emptyContextMessage;
    }

    public void setEmptyContextMessage(String emptyContextMessage) {
      this.emptyContextMessage = emptyContextMessage;
    }
  }
}
