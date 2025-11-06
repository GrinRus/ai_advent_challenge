package com.aiadvent.mcp.backend.coding;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "coding")
public class CodingAssistantProperties {

  private Duration patchTtl = Duration.ofHours(24);
  private int maxDiffBytes = 256 * 1024;
  private int maxContextBytes = 256 * 1024;

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
}
