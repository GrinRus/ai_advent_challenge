package com.aiadvent.mcp.backend.coding;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Full snapshot of the generated patch and its metadata stored in {@link PatchRegistry}.
 */
public record CodingPatch(
    String patchId,
    String workspaceId,
    PatchStatus status,
    String instructions,
    String summary,
    String diff,
    PatchAnnotations annotations,
    PatchUsage usage,
    boolean requiresManualReview,
    boolean hasDryRun,
    Instant createdAt,
    Instant updatedAt,
    Instant expiresAt,
    List<String> targetPaths,
    List<String> forbiddenPaths,
    List<ContextSnippet> contextSnippets) {

  public CodingPatch {
    if (patchId == null || patchId.isBlank()) {
      throw new IllegalArgumentException("patchId must not be blank");
    }
    if (workspaceId == null || workspaceId.isBlank()) {
      throw new IllegalArgumentException("workspaceId must not be blank");
    }
    status = Objects.requireNonNull(status, "status");
    instructions = Objects.requireNonNullElse(instructions, "").trim();
    summary = Objects.requireNonNullElse(summary, "");
    diff = Objects.requireNonNullElse(diff, "");
    annotations = annotations == null ? PatchAnnotations.empty() : annotations;
    usage = usage == null ? PatchUsage.empty() : usage;
    createdAt = Objects.requireNonNull(createdAt, "createdAt");
    updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
    targetPaths = List.copyOf(Objects.requireNonNullElse(targetPaths, List.of()));
    forbiddenPaths = List.copyOf(Objects.requireNonNullElse(forbiddenPaths, List.of()));
    contextSnippets = List.copyOf(Objects.requireNonNullElse(contextSnippets, List.of()));
  }

  public CodingPatch withPatchContent(
      String newSummary, String newDiff, PatchAnnotations newAnnotations, PatchUsage newUsage) {
    return new CodingPatch(
        patchId,
        workspaceId,
        status,
        instructions,
        Objects.requireNonNullElse(newSummary, ""),
        Objects.requireNonNullElse(newDiff, ""),
        newAnnotations == null ? PatchAnnotations.empty() : newAnnotations,
        newUsage == null ? PatchUsage.empty() : newUsage,
        requiresManualReview,
        hasDryRun,
        createdAt,
        updatedAt,
        expiresAt,
        targetPaths,
        forbiddenPaths,
        contextSnippets);
  }

  public CodingPatch withRequiresManualReview(boolean value) {
    if (this.requiresManualReview == value) {
      return this;
    }
    return new CodingPatch(
        patchId,
        workspaceId,
        status,
        instructions,
        summary,
        diff,
        annotations,
        usage,
        value,
        hasDryRun,
        createdAt,
        updatedAt,
        expiresAt,
        targetPaths,
        forbiddenPaths,
        contextSnippets);
  }

  public CodingPatch withStatus(PatchStatus newStatus) {
    if (status == newStatus) {
      return this;
    }
    return new CodingPatch(
        patchId,
        workspaceId,
        Objects.requireNonNull(newStatus, "status"),
        instructions,
        summary,
        diff,
        annotations,
        usage,
        requiresManualReview,
        hasDryRun,
        createdAt,
        updatedAt,
        expiresAt,
        targetPaths,
        forbiddenPaths,
        contextSnippets);
  }

  public CodingPatch withHasDryRun(boolean value) {
    if (hasDryRun == value) {
      return this;
    }
    return new CodingPatch(
        patchId,
        workspaceId,
        status,
        instructions,
        summary,
        diff,
        annotations,
        usage,
        requiresManualReview,
        value,
        createdAt,
        updatedAt,
        expiresAt,
        targetPaths,
        forbiddenPaths,
        contextSnippets);
  }

  public CodingPatch touch(Instant now, Duration ttl) {
    Objects.requireNonNull(now, "now");
    Objects.requireNonNull(ttl, "ttl");
    Instant refreshedExpires = now.plus(ttl);
    return new CodingPatch(
        patchId,
        workspaceId,
        status,
        instructions,
        summary,
        diff,
        annotations,
        usage,
        requiresManualReview,
        hasDryRun,
        createdAt,
        now,
        refreshedExpires,
        targetPaths,
        forbiddenPaths,
        contextSnippets);
  }

  public boolean isExpired(Instant now) {
    return expiresAt.isBefore(now);
  }
}
