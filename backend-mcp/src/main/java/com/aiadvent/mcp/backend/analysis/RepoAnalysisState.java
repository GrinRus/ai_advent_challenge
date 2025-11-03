package com.aiadvent.mcp.backend.analysis;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.stream.Collectors;

@JsonIgnoreProperties(ignoreUnknown = true)
public class RepoAnalysisState {

  private String analysisId;
  private String workspaceId;
  private String projectPath;
  private Config config;
  private Instant createdAt;
  private Instant updatedAt;
  private final Deque<FileCursor> pending = new ArrayDeque<>();
  private final List<FileCursor> processed = new ArrayList<>();
  private final List<RepoFinding> findings = new ArrayList<>();
  private final List<String> warnings = new ArrayList<>();
  private final List<String> skippedFiles = new ArrayList<>();
  private int processedSegments;

  public RepoAnalysisState() {}

  public String getAnalysisId() {
    return analysisId;
  }

  public void setAnalysisId(String analysisId) {
    this.analysisId = analysisId;
  }

  public String getWorkspaceId() {
    return workspaceId;
  }

  public void setWorkspaceId(String workspaceId) {
    this.workspaceId = workspaceId;
  }

  public String getProjectPath() {
    return projectPath;
  }

  public void setProjectPath(String projectPath) {
    this.projectPath = projectPath;
  }

  public Config getConfig() {
    return config;
  }

  public void setConfig(Config config) {
    this.config = config;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(Instant createdAt) {
    this.createdAt = createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(Instant updatedAt) {
    this.updatedAt = updatedAt;
  }

  public Deque<FileCursor> getPending() {
    return pending;
  }

  public List<FileCursor> getProcessed() {
    return processed;
  }

  public List<RepoFinding> getFindings() {
    return findings;
  }

  public List<String> getWarnings() {
    return warnings;
  }

  public List<String> getSkippedFiles() {
    return skippedFiles;
  }

  public int getProcessedSegments() {
    return processedSegments;
  }

  public void incrementProcessedSegments() {
    this.processedSegments++;
  }

  public void addWarning(String warning) {
    if (warning != null && !warning.isBlank()) {
      warnings.add(warning);
    }
  }

  public void addSkippedFile(String path) {
    if (path != null && !path.isBlank()) {
      skippedFiles.add(path);
    }
  }

  public void addFinding(RepoFinding finding) {
    if (finding != null) {
      findings.add(finding);
    }
  }

  public void addPending(FileCursor cursor) {
    if (cursor != null) {
      pending.add(cursor);
    }
  }

  public void addProcessed(FileCursor cursor) {
    if (cursor != null) {
      processed.add(cursor);
    }
  }

  public void touch() {
    this.updatedAt = Instant.now();
  }

  @JsonIgnore
  public boolean isEmpty() {
    return pending.isEmpty();
  }

  public FileCursor pollPending() {
    if (pending.isEmpty()) {
      return null;
    }
    return pending.removeFirst();
  }

  public void addPendingFirst(FileCursor cursor) {
    if (cursor != null) {
      pending.addFirst(cursor);
    }
  }

  public List<FileCursor> viewPending() {
    return Collections.unmodifiableList(new ArrayList<>(pending));
  }

  public static class Config {
    private int maxDepth;
    private long maxFileBytes;
    private long segmentMaxBytes;
    private boolean includeHidden;
    private boolean followSymlinks;
    private List<String> includeExtensions = new ArrayList<>();
    private List<String> excludeExtensions = new ArrayList<>();
    private List<String> excludeDirectories = new ArrayList<>();

    public Config() {}

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

  public static class FileCursor {
    private String path;
    private long sizeBytes;
    private boolean binary;
    private long offset;
    private int segmentIndex;
    private int totalSegments;
    private int lineOffset;
    private Instant lastModified;
    private boolean completed;

    public FileCursor() {}

    public String getPath() {
      return path;
    }

    public void setPath(String path) {
      this.path = path;
    }

    public long getSizeBytes() {
      return sizeBytes;
    }

    public void setSizeBytes(long sizeBytes) {
      this.sizeBytes = sizeBytes;
    }

    public boolean isBinary() {
      return binary;
    }

    public void setBinary(boolean binary) {
      this.binary = binary;
    }

    public long getOffset() {
      return offset;
    }

    public void setOffset(long offset) {
      this.offset = offset;
    }

    public int getSegmentIndex() {
      return segmentIndex;
    }

    public void setSegmentIndex(int segmentIndex) {
      this.segmentIndex = segmentIndex;
    }

    public int getTotalSegments() {
      return totalSegments;
    }

    public void setTotalSegments(int totalSegments) {
      this.totalSegments = totalSegments;
    }

    public int getLineOffset() {
      return lineOffset;
    }

    public void setLineOffset(int lineOffset) {
      this.lineOffset = lineOffset;
    }

    public Instant getLastModified() {
      return lastModified;
    }

    public void setLastModified(Instant lastModified) {
      this.lastModified = lastModified;
    }

    public boolean isCompleted() {
      return completed;
    }

    public void setCompleted(boolean completed) {
      this.completed = completed;
    }
  }

  public static class RepoFinding {
    private String id;
    private String path;
    private Integer line;
    private Integer endLine;
    private String title;
    private String summary;
    private String severity;
    private List<String> tags = new ArrayList<>();
    private Double score;
    private String segmentKey;
    private Instant recordedAt;

    public RepoFinding() {}

    public String getId() {
      return id;
    }

    public void setId(String id) {
      this.id = id;
    }

    public String getPath() {
      return path;
    }

    public void setPath(String path) {
      this.path = path;
    }

    public Integer getLine() {
      return line;
    }

    public void setLine(Integer line) {
      this.line = line;
    }

    public Integer getEndLine() {
      return endLine;
    }

    public void setEndLine(Integer endLine) {
      this.endLine = endLine;
    }

    public String getTitle() {
      return title;
    }

    public void setTitle(String title) {
      this.title = title;
    }

    public String getSummary() {
      return summary;
    }

    public void setSummary(String summary) {
      this.summary = summary;
    }

    public String getSeverity() {
      return severity;
    }

    public void setSeverity(String severity) {
      this.severity = severity;
    }

    public List<String> getTags() {
      return tags;
    }

    public void setTags(List<String> tags) {
      this.tags =
          tags != null
              ? tags.stream()
                  .filter(tag -> tag != null && !tag.isBlank())
                  .map(String::trim)
                  .collect(Collectors.toCollection(ArrayList::new))
              : new ArrayList<>();
    }

    public Double getScore() {
      return score;
    }

    public void setScore(Double score) {
      this.score = score;
    }

    public String getSegmentKey() {
      return segmentKey;
    }

    public void setSegmentKey(String segmentKey) {
      this.segmentKey = segmentKey;
    }

    public Instant getRecordedAt() {
      return recordedAt;
    }

    public void setRecordedAt(Instant recordedAt) {
      this.recordedAt = recordedAt;
    }
  }
}
