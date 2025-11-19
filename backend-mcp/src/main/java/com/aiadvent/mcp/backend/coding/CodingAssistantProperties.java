package com.aiadvent.mcp.backend.coding;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "coding")
public class CodingAssistantProperties {

  private Duration patchTtl = Duration.ofHours(24);
  private int maxDiffBytes = 256 * 1024;
  private int maxContextBytes = 256 * 1024;
  private int maxInstructionLength = 4000;
  private int maxFilesPerPatch = 25;
  private ClaudeCliProperties claude = new ClaudeCliProperties();
  private OpenAiProperties openai = new OpenAiProperties();

  public Duration getPatchTtl() {
    return patchTtl;
  }

  public void setPatchTtl(Duration patchTtl) {
    this.patchTtl = patchTtl;
  }

  public int getMaxDiffBytes() {
    return maxDiffBytes;
  }

  public void setMaxDiffBytes(int maxDiffBytes) {
    this.maxDiffBytes = maxDiffBytes;
  }

  public int getMaxContextBytes() {
    return maxContextBytes;
  }

  public void setMaxContextBytes(int maxContextBytes) {
    this.maxContextBytes = maxContextBytes;
  }

  public int getMaxInstructionLength() {
    return maxInstructionLength;
  }

  public void setMaxInstructionLength(int maxInstructionLength) {
    this.maxInstructionLength = maxInstructionLength;
  }

  public int getMaxFilesPerPatch() {
    return maxFilesPerPatch;
  }

  public void setMaxFilesPerPatch(int maxFilesPerPatch) {
    this.maxFilesPerPatch = maxFilesPerPatch;
  }

  public ClaudeCliProperties getClaude() {
    return claude;
  }

  public void setClaude(ClaudeCliProperties claude) {
    this.claude = claude;
  }

  public OpenAiProperties getOpenai() {
    return openai;
  }

  public void setOpenai(OpenAiProperties openai) {
    this.openai = openai;
  }

  public static class ClaudeCliProperties {

    private boolean enabled = true;
    private String baseUrl = "https://api.z.ai/api/anthropic";
    private String model = "GLM-4.6";
    private String apiKey;
    private int maxRetries = 1;
    private String cliBin = "claude";
    private Duration cliTimeout = Duration.ofMinutes(2);
    private Duration apiTimeout = Duration.ofMinutes(50);
    private String defaultOpusModel = "GLM-4.6";
    private String defaultSonnetModel = "GLM-4.6";
    private String defaultHaikuModel = "GLM-4.5-Air";

    public boolean isEnabled() {
      return enabled;
    }

    public void setEnabled(boolean enabled) {
      this.enabled = enabled;
    }

    public String getBaseUrl() {
      return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
      this.baseUrl = baseUrl;
    }

    public String getModel() {
      return model;
    }

    public void setModel(String model) {
      this.model = model;
    }

    public String getApiKey() {
      return apiKey;
    }

    public void setApiKey(String apiKey) {
      this.apiKey = apiKey;
    }

    public int getMaxRetries() {
      return maxRetries;
    }

    public void setMaxRetries(int maxRetries) {
      this.maxRetries = maxRetries;
    }

    public String getCliBin() {
      return cliBin;
    }

    public void setCliBin(String cliBin) {
      this.cliBin = cliBin;
    }

    public Duration getCliTimeout() {
      return cliTimeout;
    }

    public void setCliTimeout(Duration cliTimeout) {
      this.cliTimeout = cliTimeout;
    }

    public Duration getApiTimeout() {
      return apiTimeout;
    }

    public void setApiTimeout(Duration apiTimeout) {
      this.apiTimeout = apiTimeout;
    }

    public String getDefaultOpusModel() {
      return defaultOpusModel;
    }

    public void setDefaultOpusModel(String defaultOpusModel) {
      this.defaultOpusModel = defaultOpusModel;
    }

    public String getDefaultSonnetModel() {
      return defaultSonnetModel;
    }

    public void setDefaultSonnetModel(String defaultSonnetModel) {
      this.defaultSonnetModel = defaultSonnetModel;
    }

    public String getDefaultHaikuModel() {
      return defaultHaikuModel;
    }

    public void setDefaultHaikuModel(String defaultHaikuModel) {
      this.defaultHaikuModel = defaultHaikuModel;
    }
  }

  public static class OpenAiProperties {

    private boolean enabled = true;
    private String model = "gpt-4o-mini";
    private double temperature = 0.2;
    private int maxTokens = 2048;
    private int maxOperations = 8;
    private int maxFileLines = 2000;
    private int maxFileBytes = 200 * 1024;

    public boolean isEnabled() {
      return enabled;
    }

    public void setEnabled(boolean enabled) {
      this.enabled = enabled;
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

    public int getMaxTokens() {
      return maxTokens;
    }

    public void setMaxTokens(int maxTokens) {
      this.maxTokens = maxTokens;
    }

    public int getMaxOperations() {
      return maxOperations;
    }

    public void setMaxOperations(int maxOperations) {
      this.maxOperations = maxOperations;
    }

    public int getMaxFileLines() {
      return maxFileLines;
    }

    public void setMaxFileLines(int maxFileLines) {
      this.maxFileLines = maxFileLines;
    }

    public int getMaxFileBytes() {
      return maxFileBytes;
    }

    public void setMaxFileBytes(int maxFileBytes) {
      this.maxFileBytes = maxFileBytes;
    }
  }
}
