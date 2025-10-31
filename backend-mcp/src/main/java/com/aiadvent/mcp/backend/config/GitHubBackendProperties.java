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
  private String workspaceRoot = "/var/tmp/aiadvent/mcp-workspaces";
  private Duration workspaceTtl = Duration.ofHours(24);
  private Duration workspaceCleanupInterval = Duration.ofMinutes(15);
  private Long workspaceMaxSizeBytes = 2L * 1024 * 1024 * 1024;
  private Long archiveMaxSizeBytes = 512L * 1024 * 1024;
  private Duration archiveDownloadTimeout = Duration.ofMinutes(2);

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

  public String getWorkspaceRoot() {
    return workspaceRoot;
  }

  public void setWorkspaceRoot(String workspaceRoot) {
    this.workspaceRoot = workspaceRoot;
  }

  public Duration getWorkspaceTtl() {
    return workspaceTtl;
  }

  public void setWorkspaceTtl(Duration workspaceTtl) {
    this.workspaceTtl = workspaceTtl;
  }

  public Duration getWorkspaceCleanupInterval() {
    return workspaceCleanupInterval;
  }

  public void setWorkspaceCleanupInterval(Duration workspaceCleanupInterval) {
    this.workspaceCleanupInterval = workspaceCleanupInterval;
  }

  public Long getWorkspaceMaxSizeBytes() {
    return workspaceMaxSizeBytes;
  }

  public void setWorkspaceMaxSizeBytes(Long workspaceMaxSizeBytes) {
    this.workspaceMaxSizeBytes = workspaceMaxSizeBytes;
  }

  public Long getArchiveMaxSizeBytes() {
    return archiveMaxSizeBytes;
  }

  public void setArchiveMaxSizeBytes(Long archiveMaxSizeBytes) {
    this.archiveMaxSizeBytes = archiveMaxSizeBytes;
  }

  public Duration getArchiveDownloadTimeout() {
    return archiveDownloadTimeout;
  }

  public void setArchiveDownloadTimeout(Duration archiveDownloadTimeout) {
    this.archiveDownloadTimeout = archiveDownloadTimeout;
  }
}
