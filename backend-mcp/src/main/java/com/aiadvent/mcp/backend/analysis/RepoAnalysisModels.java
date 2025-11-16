package com.aiadvent.mcp.backend.analysis;

import java.time.Instant;
import java.util.List;

public final class RepoAnalysisModels {

  private RepoAnalysisModels() {}

  public record ScanNextSegmentRequest(
      String analysisId,
      String workspaceId,
      String projectPath,
      Boolean reset,
      Long maxBytes,
      ScanConfigOverrides configOverrides) {}

  public record ScanConfigOverrides(
      Integer maxDepth,
      Long maxFileBytes,
      Long segmentMaxBytes,
      Boolean includeHidden,
      List<String> includeExtensions,
      List<String> excludeExtensions,
      List<String> excludeDirectories) {}

  public record ScanNextSegmentResponse(
      String analysisId,
      String workspaceId,
      String projectPath,
      boolean completed,
      Segment segment,
      int remainingSegments,
      int processedSegments,
      List<String> warnings,
      List<String> skippedFiles,
      Instant generatedAt) {}

  public record Segment(
      String key,
      String path,
      int segmentIndex,
      int totalSegments,
      int startLine,
      int endLine,
      long bytes,
      boolean truncated,
      String content,
      String summary,
      Instant readAt,
      String contentHash,
      boolean duplicate,
      List<String> tags,
      SegmentMetadata metadata) {}

  public record AggregateFindingsRequest(
      String analysisId,
      String workspaceId,
      List<FindingInput> findings) {}

  public record FindingInput(
      String path,
      Integer line,
      Integer endLine,
      String title,
      String summary,
      String severity,
      List<String> tags,
      Double score) {}

  public record AggregateFindingsResponse(
      String analysisId,
      String workspaceId,
      int totalFindings,
      int newFindings,
      List<FileFindings> files,
      Instant aggregatedAt) {}

  public record FileFindings(
      String path,
      int findingCount,
      String worstSeverity,
      double score,
      List<String> tags,
      List<String> highlights) {}

  public record ListHotspotsRequest(
      String analysisId,
      String workspaceId,
      Integer limit,
      Boolean includeDetails) {}

  public record ListHotspotsResponse(
      String analysisId,
      String workspaceId,
      List<Hotspot> hotspots,
      Instant generatedAt) {}

  public record Hotspot(
      String path,
      String severity,
      int findingCount,
      double score,
      double priority,
      String sourceCategory,
      List<String> highlights,
      List<String> tags) {}

  public record SegmentMetadata(
      List<String> projectTypes,
      List<String> packageManagers,
      InfrastructureFlags infrastructureFlags) {}

  public record InfrastructureFlags(
      boolean hasTerraform,
      boolean hasHelm,
      boolean hasCompose,
      boolean hasDbMigrations,
      boolean hasFeatureFlags) {}
}
