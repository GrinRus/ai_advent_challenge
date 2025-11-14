package com.aiadvent.mcp.backend.coding;

import java.nio.file.Path;
import java.util.List;

/**
 * Abstraction for generating patches (diffs, summary, annotations) for the assisted-coding flow.
 */
public interface PatchGenerator {

  GenerationResult generate(GeneratePatchCommand command);

  record GeneratePatchCommand(
      String patchId,
      String workspaceId,
      Path workspacePath,
      String instructions,
      List<String> targetPaths,
      List<String> forbiddenPaths,
      List<ContextSnippet> contextSnippets) {}

  record GenerationResult(
      String summary,
      String diff,
      PatchAnnotations annotations,
      PatchUsage usage,
      boolean requiresManualReview) {}
}
