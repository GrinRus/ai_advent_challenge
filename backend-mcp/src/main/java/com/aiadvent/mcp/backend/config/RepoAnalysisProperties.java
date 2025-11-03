package com.aiadvent.mcp.backend.config;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "repo.analysis")
public class RepoAnalysisProperties {

  private String stateRoot =
      Path.of(System.getProperty("java.io.tmpdir"), "aiadvent", "mcp", "repo-analysis")
          .toString();

  private int maxDepth = 8;
  private long maxFileBytes = 512 * 1024;
  private long segmentMaxBytes = 32 * 1024;
  private boolean includeHidden = false;
  private boolean followSymlinks = false;
  private List<String> includeExtensions = new ArrayList<>();
  private List<String> excludeExtensions =
      new ArrayList<>(
          List.of(
              "png",
              "jpg",
              "jpeg",
              "gif",
              "bmp",
              "ico",
              "class",
              "exe",
              "dll",
              "so",
              "dylib",
              "lock",
              "zip",
              "jar",
              "war",
              "mp4",
              "mp3",
              "ogg",
              "avi",
              "mov",
              "pdf",
              "bin"));
  private List<String> excludeDirectories =
      new ArrayList<>(
          List.of(".git", "node_modules", "build", "dist", "out", "target", ".idea", ".gradle"));

  public String getStateRoot() {
    return stateRoot;
  }

  public void setStateRoot(String stateRoot) {
    this.stateRoot = stateRoot;
  }

  public Path stateRootPath() {
    return Path.of(stateRoot);
  }

  public int getMaxDepth() {
    return maxDepth;
  }

  public void setMaxDepth(int maxDepth) {
    this.maxDepth = maxDepth;
  }

  public long getMaxFileBytes() {
    return maxFileBytes;
  }

  public void setMaxFileBytes(long maxFileBytes) {
    this.maxFileBytes = maxFileBytes;
  }

  public long getSegmentMaxBytes() {
    return segmentMaxBytes;
  }

  public void setSegmentMaxBytes(long segmentMaxBytes) {
    this.segmentMaxBytes = segmentMaxBytes;
  }

  public boolean isIncludeHidden() {
    return includeHidden;
  }

  public void setIncludeHidden(boolean includeHidden) {
    this.includeHidden = includeHidden;
  }

  public boolean isFollowSymlinks() {
    return followSymlinks;
  }

  public void setFollowSymlinks(boolean followSymlinks) {
    this.followSymlinks = followSymlinks;
  }

  public List<String> getIncludeExtensions() {
    return includeExtensions;
  }

  public void setIncludeExtensions(List<String> includeExtensions) {
    this.includeExtensions =
        includeExtensions != null ? new ArrayList<>(includeExtensions) : new ArrayList<>();
  }

  public List<String> getExcludeExtensions() {
    return excludeExtensions;
  }

  public void setExcludeExtensions(List<String> excludeExtensions) {
    this.excludeExtensions =
        excludeExtensions != null ? new ArrayList<>(excludeExtensions) : new ArrayList<>();
  }

  public List<String> getExcludeDirectories() {
    return excludeDirectories;
  }

  public void setExcludeDirectories(List<String> excludeDirectories) {
    this.excludeDirectories =
        excludeDirectories != null ? new ArrayList<>(excludeDirectories) : new ArrayList<>();
  }
}
