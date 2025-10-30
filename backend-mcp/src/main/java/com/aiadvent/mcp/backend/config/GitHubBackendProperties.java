package com.aiadvent.mcp.backend.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "github.backend")
public class GitHubBackendProperties {

  private String baseUrl = "https://api.github.com";
  private String appId;
  private Long installationId;
  private String privateKeyBase64;
  private String personalAccessToken;
  private Duration connectTimeout = Duration.ofSeconds(10);
  private Duration readTimeout = Duration.ofSeconds(30);
  private Duration appJwtTtl = Duration.ofMinutes(8);
  private Duration tokenRefreshSkew = Duration.ofMinutes(1);
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

  public String getAppId() {
    return appId;
  }

  public void setAppId(String appId) {
    this.appId = appId;
  }

  public Long getInstallationId() {
    return installationId;
  }

  public void setInstallationId(Long installationId) {
    this.installationId = installationId;
  }

  public String getPrivateKeyBase64() {
    return privateKeyBase64;
  }

  public void setPrivateKeyBase64(String privateKeyBase64) {
    this.privateKeyBase64 = privateKeyBase64;
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

  public Duration getAppJwtTtl() {
    return appJwtTtl;
  }

  public void setAppJwtTtl(Duration appJwtTtl) {
    this.appJwtTtl = appJwtTtl;
  }

  public Duration getTokenRefreshSkew() {
    return tokenRefreshSkew;
  }

  public void setTokenRefreshSkew(Duration tokenRefreshSkew) {
    this.tokenRefreshSkew = tokenRefreshSkew;
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

