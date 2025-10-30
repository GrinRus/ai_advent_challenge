package com.aiadvent.mcp.backend.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "github.backend")
public class GitHubBackendProperties {

  private String baseUrl = "https://api.github.com";
  private String personalAccessToken;
  private Duration connectTimeout = Duration.ofSeconds(10);
  private Duration readTimeout = Duration.ofSeconds(30);
  private Duration treeCacheTtl = Duration.ofMinutes(2);
  private Duration fileCacheTtl = Duration.ofMinutes(2);
  private Integer treeMaxDepth = 3;
  private Integer treeMaxEntries = 500;
  private Long fileMaxSizeBytes = 512 * 1024L;
  private String userAgent = "AI Advent GitHub MCP/0.1";

  public String getBaseUrl() {
    return baseUrl;
  }

  public void setBaseUrl(String baseUrl) {
    this.baseUrl = baseUrl;
  }

  public String getPersonalAccessToken() {
    return personalAccessToken;
  }

  public void setPersonalAccessToken(String personalAccessToken) {
    this.personalAccessToken = personalAccessToken;
  }

  public Duration getConnectTimeout() {
    return connectTimeout;
  }

  public void setConnectTimeout(Duration connectTimeout) {
    this.connectTimeout = connectTimeout;
  }

  public Duration getReadTimeout() {
    return readTimeout;
  }

  public void setReadTimeout(Duration readTimeout) {
    this.readTimeout = readTimeout;
  }

  public Duration getTreeCacheTtl() {
    return treeCacheTtl;
  }

  public void setTreeCacheTtl(Duration treeCacheTtl) {
    this.treeCacheTtl = treeCacheTtl;
  }

  public Duration getFileCacheTtl() {
    return fileCacheTtl;
  }

  public void setFileCacheTtl(Duration fileCacheTtl) {
    this.fileCacheTtl = fileCacheTtl;
  }

  public Integer getTreeMaxDepth() {
    return treeMaxDepth;
  }

  public void setTreeMaxDepth(Integer treeMaxDepth) {
    this.treeMaxDepth = treeMaxDepth;
  }

  public Integer getTreeMaxEntries() {
    return treeMaxEntries;
  }

  public void setTreeMaxEntries(Integer treeMaxEntries) {
    this.treeMaxEntries = treeMaxEntries;
  }

  public Long getFileMaxSizeBytes() {
    return fileMaxSizeBytes;
  }

  public void setFileMaxSizeBytes(Long fileMaxSizeBytes) {
    this.fileMaxSizeBytes = fileMaxSizeBytes;
  }

  public String getUserAgent() {
    return userAgent;
  }

  public void setUserAgent(String userAgent) {
    this.userAgent = userAgent;
  }
}
