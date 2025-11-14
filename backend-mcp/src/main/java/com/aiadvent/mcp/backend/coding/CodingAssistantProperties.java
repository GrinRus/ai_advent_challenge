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

  public static class ClaudeCliProperties {

    private boolean enabled = true;
    private String baseUrl = "https://api.z.ai/api/anthropic";
    private String model = "GLM-4.5";
    private String apiKey;
    private int maxRetries = 1;
    private String cliBin = "claude";
    private Duration cliTimeout = Duration.ofMinutes(2);

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
  }
}
