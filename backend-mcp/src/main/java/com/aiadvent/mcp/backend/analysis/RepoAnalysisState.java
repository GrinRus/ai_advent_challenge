package com.aiadvent.mcp.backend.analysis;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@JsonIgnoreProperties(ignoreUnknown = true)
public class RepoAnalysisState {

  private String analysisId;
  private String workspaceId;
  private String projectPath;
  private Config config;
  private Instant createdAt;
  private Instant updatedAt;
  private final List<FileCursor> pending = new ArrayList<>();
  private final List<FileCursor> processed = new ArrayList<>();
  private final List<RepoFinding> findings = new ArrayList<>();
  private final List<String> warnings = new ArrayList<>();
  private final List<String> skippedFiles = new ArrayList<>();
  private final List<String> recentSegmentHashes = new ArrayList<>();
  private final Set<String> findingSignatures = new LinkedHashSet<>();
  private final Map<String, List<String>> heuristicsByPath = new HashMap<>();
  private Instant reportGeneratedAt;
  private String reportJsonPath;
  private String reportMarkdownPath;
  private WorkspaceMetadata workspaceMetadata;
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

  public List<FileCursor> getPending() {
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

  public Instant getReportGeneratedAt() {
    return reportGeneratedAt;
  }

  public void setReportGeneratedAt(Instant reportGeneratedAt) {
    this.reportGeneratedAt = reportGeneratedAt;
  }

  public String getReportJsonPath() {
    return reportJsonPath;
  }

  public void setReportJsonPath(String reportJsonPath) {
    this.reportJsonPath = reportJsonPath;
  }

  public String getReportMarkdownPath() {
    return reportMarkdownPath;
  }

  public void setReportMarkdownPath(String reportMarkdownPath) {
    this.reportMarkdownPath = reportMarkdownPath;
  }

  public WorkspaceMetadata getWorkspaceMetadata() {
    return workspaceMetadata;
  }

  public void setWorkspaceMetadata(WorkspaceMetadata workspaceMetadata) {
    this.workspaceMetadata = workspaceMetadata;
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
    int bestIndex = 0;
    double bestWeight = Double.NEGATIVE_INFINITY;
    String bestPath = null;
    for (int i = 0; i < pending.size(); i++) {
      FileCursor candidate = pending.get(i);
      double weight = candidate != null ? candidate.getPriorityWeight() : 0.0d;
      String path = candidate != null ? candidate.getPath() : null;
      if (weight > bestWeight) {
        bestWeight = weight;
        bestIndex = i;
        bestPath = path;
      } else if (weight == bestWeight && path != null && bestPath != null && path.compareTo(bestPath) < 0) {
        bestIndex = i;
        bestPath = path;
      }
    }
    return pending.remove(bestIndex);
  }

  public void addPendingFirst(FileCursor cursor) {
    if (cursor != null) {
      double boosted = Math.max(cursor.getPriorityWeight(), 0.0d) + 0.1d;
      cursor.setPriorityWeight(boosted);
      pending.add(cursor);
    }
  }

  public List<FileCursor> viewPending() {
    return Collections.unmodifiableList(new ArrayList<>(pending));
  }

  public boolean registerSegmentHash(String hash, int maxEntries) {
    if (hash == null || hash.isBlank()) {
      return false;
    }
    if (recentSegmentHashes.contains(hash)) {
      return false;
    }
    recentSegmentHashes.add(hash);
    while (recentSegmentHashes.size() > maxEntries) {
      recentSegmentHashes.remove(0);
    }
    return true;
  }

  public boolean registerFindingSignature(String signature) {
    if (signature == null || signature.isBlank()) {
      return false;
    }
    return findingSignatures.add(signature);
  }

  public void recordHeuristics(String path, List<String> tags) {
    if (path == null || path.isBlank() || tags == null || tags.isEmpty()) {
      return;
    }
    heuristicsByPath.put(path, List.copyOf(tags));
  }

  public List<String> resolveHeuristics(String path) {
    if (path == null) {
      return List.of();
    }
    return heuristicsByPath.getOrDefault(path, List.of());
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
    private boolean advancedMetricsEnabled;

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

    public boolean isAdvancedMetricsEnabled() {
      return advancedMetricsEnabled;
    }

    public void setAdvancedMetricsEnabled(boolean advancedMetricsEnabled) {
      this.advancedMetricsEnabled = advancedMetricsEnabled;
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
    private String fileType;
    private double priorityWeight;

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

    public String getFileType() {
      return fileType;
    }

    public void setFileType(String fileType) {
      this.fileType = fileType;
    }

    public double getPriorityWeight() {
      return priorityWeight;
    }

    public void setPriorityWeight(double priorityWeight) {
      this.priorityWeight = priorityWeight;
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
    private String category;

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

    public String getCategory() {
      return category;
    }

    public void setCategory(String category) {
      this.category = category;
    }
  }

  public static class WorkspaceMetadata {
    private List<String> projectTypes = new ArrayList<>();
    private List<String> packageManagers = new ArrayList<>();
    private InfrastructureFlags infrastructureFlags = new InfrastructureFlags();

    public WorkspaceMetadata() {}

    public List<String> getProjectTypes() {
      return projectTypes;
    }

    public void setProjectTypes(List<String> projectTypes) {
      this.projectTypes =
          projectTypes != null ? new ArrayList<>(projectTypes) : new ArrayList<>();
    }

    public List<String> getPackageManagers() {
      return packageManagers;
    }

    public void setPackageManagers(List<String> packageManagers) {
      this.packageManagers =
          packageManagers != null ? new ArrayList<>(packageManagers) : new ArrayList<>();
    }

    public InfrastructureFlags getInfrastructureFlags() {
      return infrastructureFlags;
    }

    public void setInfrastructureFlags(InfrastructureFlags infrastructureFlags) {
      this.infrastructureFlags =
          infrastructureFlags != null ? infrastructureFlags : new InfrastructureFlags();
    }
  }

  public static class InfrastructureFlags {
    private boolean hasTerraform;
    private boolean hasHelm;
    private boolean hasCompose;
    private boolean hasDbMigrations;
    private boolean hasFeatureFlags;

    public InfrastructureFlags() {}

    public InfrastructureFlags(
        boolean hasTerraform,
        boolean hasHelm,
        boolean hasCompose,
        boolean hasDbMigrations,
        boolean hasFeatureFlags) {
      this.hasTerraform = hasTerraform;
      this.hasHelm = hasHelm;
      this.hasCompose = hasCompose;
      this.hasDbMigrations = hasDbMigrations;
      this.hasFeatureFlags = hasFeatureFlags;
    }

    public boolean isHasTerraform() {
      return hasTerraform;
    }

    public void setHasTerraform(boolean hasTerraform) {
      this.hasTerraform = hasTerraform;
    }

    public boolean isHasHelm() {
      return hasHelm;
    }

    public void setHasHelm(boolean hasHelm) {
      this.hasHelm = hasHelm;
    }

    public boolean isHasCompose() {
      return hasCompose;
    }

    public void setHasCompose(boolean hasCompose) {
      this.hasCompose = hasCompose;
    }

    public boolean isHasDbMigrations() {
      return hasDbMigrations;
    }

    public void setHasDbMigrations(boolean hasDbMigrations) {
      this.hasDbMigrations = hasDbMigrations;
    }

    public boolean isHasFeatureFlags() {
      return hasFeatureFlags;
    }

    public void setHasFeatureFlags(boolean hasFeatureFlags) {
      this.hasFeatureFlags = hasFeatureFlags;
    }
  }
}
