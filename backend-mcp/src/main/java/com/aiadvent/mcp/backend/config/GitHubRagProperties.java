package com.aiadvent.mcp.backend.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "github.rag")
public class GitHubRagProperties {

  private String namespacePrefix = "repo";
  private int maxConcurrency = 2;
  private final Chunk chunk = new Chunk();
  private final Retry retry = new Retry();
  private final Ignore ignore = new Ignore();
  private final Embedding embedding = new Embedding();
  private final Rerank rerank = new Rerank();
  private final QueryTransformers queryTransformers = new QueryTransformers();
  private final MultiQuery multiQuery = new MultiQuery();
  private final PostProcessing postProcessing = new PostProcessing();

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

  public Chunk getChunk() {
    return chunk;
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

  public static class Chunk {
    private int maxBytes = 2048;
    private int maxLines = 160;

    public int getMaxBytes() {
      return maxBytes;
    }

    public void setMaxBytes(int maxBytes) {
      this.maxBytes = maxBytes;
    }

    public int getMaxLines() {
      return maxLines;
    }

    public void setMaxLines(int maxLines) {
      this.maxLines = maxLines;
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
  }
}
