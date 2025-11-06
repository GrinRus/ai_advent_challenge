package com.aiadvent.mcp.backend.coding;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

@Component
class PatchRegistry implements InitializingBean, DisposableBean {

  private static final Logger log = LoggerFactory.getLogger(PatchRegistry.class);

  private final Duration ttl;
  private final Duration cleanupInterval;
  private final ScheduledExecutorService cleanupExecutor;
  private final Map<String, PatchHolder> patches = new ConcurrentHashMap<>();

  PatchRegistry(CodingAssistantProperties properties) {
    Objects.requireNonNull(properties, "properties");
    Duration configuredTtl =
        Optional.ofNullable(properties.getPatchTtl()).orElse(Duration.ofHours(24));
    if (configuredTtl.isZero() || configuredTtl.isNegative()) {
      throw new IllegalArgumentException("patchTtl must be positive");
    }
    this.ttl = configuredTtl;
    Duration candidate = configuredTtl.dividedBy(4);
    if (candidate.isZero() || candidate.isNegative()) {
      candidate = Duration.ofMinutes(5);
    } else if (candidate.compareTo(Duration.ofMinutes(1)) < 0) {
      candidate = Duration.ofMinutes(1);
    } else if (candidate.compareTo(Duration.ofMinutes(30)) > 0) {
      candidate = Duration.ofMinutes(30);
    }
    this.cleanupInterval = candidate;
    this.cleanupExecutor =
        Executors.newSingleThreadScheduledExecutor(
            runnable -> {
              Thread thread = new Thread(runnable, "coding-patch-registry-cleanup");
              thread.setDaemon(true);
              return thread;
            });
  }

  @Override
  public void afterPropertiesSet() {
    cleanupExecutor.scheduleAtFixedRate(
        this::cleanupExpired,
        cleanupInterval.toMillis(),
        cleanupInterval.toMillis(),
        TimeUnit.MILLISECONDS);
  }

  @Override
  public void destroy() {
    cleanupExecutor.shutdownNow();
  }

  CodingPatch register(NewPatch newPatch) {
    Objects.requireNonNull(newPatch, "newPatch");
    String patchId = requirePatchId(newPatch.patchId());
    String workspaceId = sanitizeWorkspaceId(newPatch.workspaceId());
    Instant now = Instant.now();
    CodingPatch patch =
        new CodingPatch(
            patchId,
            workspaceId,
            PatchStatus.GENERATED,
            Objects.requireNonNullElse(newPatch.instructions(), ""),
            Objects.requireNonNullElse(newPatch.summary(), ""),
            Objects.requireNonNullElse(newPatch.diff(), ""),
            newPatch.annotations() == null ? PatchAnnotations.empty() : newPatch.annotations(),
            newPatch.usage() == null ? PatchUsage.empty() : newPatch.usage(),
            newPatch.requiresManualReview(),
            newPatch.hasDryRun(),
            now,
            now,
            now.plus(ttl),
            newPatch.targetPaths(),
            newPatch.forbiddenPaths(),
            newPatch.contextSnippets());
    PatchHolder holder = new PatchHolder(patch);
    PatchHolder existing = patches.putIfAbsent(patchId, holder);
    if (existing != null) {
      throw new IllegalArgumentException("Patch with id %s already exists".formatted(patchId));
    }
    log.debug("Registered patch {} for workspace {}", patch.patchId(), patch.workspaceId());
    return patch;
  }

  CodingPatch get(String workspaceId, String patchId) {
    return updateInternal(workspaceId, patchId, UnaryOperator.identity());
  }

  CodingPatch update(
      String workspaceId, String patchId, UnaryOperator<CodingPatch> updater) {
    Objects.requireNonNull(updater, "updater");
    return updateInternal(workspaceId, patchId, updater);
  }

  CodingPatch markDryRun(String workspaceId, String patchId, boolean hasDryRun) {
    return update(
        workspaceId, patchId, patch -> patch.withHasDryRun(hasDryRun));
  }

  CodingPatch updateStatus(String workspaceId, String patchId, PatchStatus status) {
    Objects.requireNonNull(status, "status");
    return update(workspaceId, patchId, patch -> patch.withStatus(status));
  }

  void cleanupExpired() {
    Instant now = Instant.now();
    for (Map.Entry<String, PatchHolder> entry : patches.entrySet()) {
      PatchHolder holder = entry.getValue();
      CodingPatch patch = holder.get();
      if (patch == null) {
        continue;
      }
      if (patch.isExpired(now)) {
        if (patches.remove(entry.getKey(), holder)) {
          log.debug(
              "Removed expired patch {} for workspace {}", patch.patchId(), patch.workspaceId());
        }
      }
    }
  }

  private CodingPatch updateInternal(
      String workspaceId, String patchId, UnaryOperator<CodingPatch> updater) {
    String sanitizedWorkspaceId = sanitizeWorkspaceId(workspaceId);
    String sanitizedPatchId = requirePatchId(patchId);
    while (true) {
      PatchHolder holder = patches.get(sanitizedPatchId);
      if (holder == null) {
        throw new IllegalArgumentException("Unknown patchId: " + sanitizedPatchId);
      }
      CodingPatch current = holder.get();
      Instant now = Instant.now();
      if (current == null || current.isExpired(now)) {
        patches.remove(sanitizedPatchId, holder);
        throw new IllegalArgumentException("Patch %s has expired".formatted(sanitizedPatchId));
      }
      if (!current.workspaceId().equals(sanitizedWorkspaceId)) {
        throw new IllegalArgumentException(
            "Patch %s does not belong to workspaceId %s"
                .formatted(sanitizedPatchId, sanitizedWorkspaceId));
      }
      CodingPatch updated =
          Objects.requireNonNull(updater.apply(current), "updater returned null");
      CodingPatch refreshed = updated.touch(now, ttl);
      if (holder.compareAndSet(current, refreshed)) {
        return refreshed;
      }
    }
  }

  private String sanitizeWorkspaceId(String workspaceId) {
    if (workspaceId == null || workspaceId.isBlank()) {
      throw new IllegalArgumentException("workspaceId must not be blank");
    }
    return workspaceId.trim();
  }

  private String requirePatchId(String patchId) {
    if (patchId == null || patchId.isBlank()) {
      throw new IllegalArgumentException("patchId must not be blank");
    }
    return patchId.trim();
  }

  private static final class PatchHolder {
    private final AtomicReference<CodingPatch> reference;

    private PatchHolder(CodingPatch patch) {
      this.reference = new AtomicReference<>(Objects.requireNonNull(patch, "patch"));
    }

    private CodingPatch get() {
      return reference.get();
    }

    private boolean compareAndSet(CodingPatch expected, CodingPatch updated) {
      return reference.compareAndSet(expected, updated);
    }
  }

  record NewPatch(
      String patchId,
      String workspaceId,
      String instructions,
      String summary,
      String diff,
      PatchAnnotations annotations,
      PatchUsage usage,
      boolean requiresManualReview,
      boolean hasDryRun,
      List<String> targetPaths,
      List<String> forbiddenPaths,
      List<ContextSnippet> contextSnippets) {}
}
