package com.aiadvent.mcp.backend.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "github.rag")
public class GitHubRagProperties {

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
