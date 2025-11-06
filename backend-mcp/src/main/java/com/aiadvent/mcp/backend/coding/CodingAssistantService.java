package com.aiadvent.mcp.backend.coding;

import com.aiadvent.mcp.backend.github.workspace.TempWorkspaceService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
class CodingAssistantService {

  private final TempWorkspaceService workspaceService;
  private final CodingAssistantProperties properties;
  private final Map<String, PatchRecord> patches = new ConcurrentHashMap<>();

  CodingAssistantService(
      TempWorkspaceService workspaceService, CodingAssistantProperties properties) {
    this.workspaceService = Objects.requireNonNull(workspaceService, "workspaceService");
    this.properties = Objects.requireNonNull(properties, "properties");
  }

  GeneratePatchResponse generatePatch(GeneratePatchRequest request) {
    Objects.requireNonNull(request, "request");
    String workspaceId = sanitizeWorkspaceId(request.workspaceId());
    String instructions =
        Optional.ofNullable(request.instructions()).map(String::trim).orElse("");
    if (!StringUtils.hasText(instructions)) {
      throw new IllegalArgumentException("instructions must not be blank");
    }

    // ensure workspace exists
    workspaceService
        .findWorkspace(workspaceId)
        .orElseThrow(() -> new IllegalArgumentException("Unknown workspaceId: " + workspaceId));

    String patchId = UUID.randomUUID().toString();
    PatchRecord record =
        new PatchRecord(
            patchId,
            workspaceId,
            instructions,
            "",
            Instant.now(),
            List.copyOf(Optional.ofNullable(request.targetPaths()).orElse(List.of())),
            List.copyOf(Optional.ofNullable(request.forbiddenPaths()).orElse(List.of())));
    patches.put(patchId, record);

    Annotations annotations =
        new Annotations(List.of(), List.of("Patch generation stub – implement LLM call"), List.of());
    Usage usage = new Usage(0, 0);
    return new GeneratePatchResponse(
        patchId,
        workspaceId,
        "Инструкция сохранена, генерация патча будет реализована позднее.",
        record.diff(),
        annotations,
        usage,
        record.createdAt());
  }

  ReviewPatchResponse reviewPatch(ReviewPatchRequest request) {
    Objects.requireNonNull(request, "request");
    PatchRecord record = findPatch(request.workspaceId(), request.patchId());
    List<String> findings = new ArrayList<>();
    findings.add("Ревью пока не реализовано для патча " + record.patchId());
    List<String> recommendations = List.of("Запланировать реализацию LLM code review.");
    List<String> nextSteps = List.of("Повторно вызвать review после реализации.");
    return new ReviewPatchResponse(
        record.patchId(), "not_implemented", findings, recommendations, nextSteps);
  }

  ApplyPatchPreviewResponse applyPatchPreview(ApplyPatchPreviewRequest request) {
    Objects.requireNonNull(request, "request");
    PatchRecord record = findPatch(request.workspaceId(), request.patchId());
    boolean dryRun = request.dryRun() == null ? true : request.dryRun();
    Preview preview =
        new Preview(
            "Dry-run "
                + (dryRun ? "пока не выполняется" : "пропущен")
                + "; патч сохранён без применения.",
            List.of());
    GradleResult gradle =
        new GradleResult(
            false, null, 0, "not_executed", List.of("Gradle проверки не запускались"));
    return new ApplyPatchPreviewResponse(
        record.patchId(),
        record.workspaceId(),
        false,
        preview,
        gradle,
        Map.of("status", "stub"),
        Instant.now());
  }

  private PatchRecord findPatch(String workspaceId, String patchId) {
    String sanitizedWorkspaceId = sanitizeWorkspaceId(workspaceId);
    if (!StringUtils.hasText(patchId)) {
      throw new IllegalArgumentException("patchId must not be blank");
    }
    PatchRecord record =
        Optional.ofNullable(patches.get(patchId))
            .orElseThrow(() -> new IllegalArgumentException("Unknown patchId: " + patchId));
    if (!record.workspaceId().equals(sanitizedWorkspaceId)) {
      throw new IllegalArgumentException("Patch does not belong to workspaceId " + workspaceId);
    }
    return record;
  }

  private String sanitizeWorkspaceId(String workspaceId) {
    String sanitized =
        Optional.ofNullable(workspaceId).map(String::trim).orElse("");
    if (!StringUtils.hasText(sanitized)) {
      throw new IllegalArgumentException("workspaceId must not be blank");
    }
    return sanitized;
  }

  private record PatchRecord(
      String patchId,
      String workspaceId,
      String instructions,
      String diff,
      Instant createdAt,
      List<String> targetPaths,
      List<String> forbiddenPaths) {}

  record GeneratePatchRequest(
      String workspaceId,
      String instructions,
      List<String> targetPaths,
      List<String> forbiddenPaths,
      List<ContextFile> contextFiles) {}

  record ContextFile(String path, Integer maxBytes) {}

  record GeneratePatchResponse(
      String patchId,
      String workspaceId,
      String summary,
      String diff,
      Annotations annotations,
      Usage usage,
      Instant createdAt) {}

  record Annotations(List<String> files, List<String> risks, List<String> conflicts) {}

  record Usage(int promptTokens, int completionTokens) {}

  record ReviewPatchRequest(String workspaceId, String patchId, List<String> focus) {}

  record ReviewPatchResponse(
      String patchId,
      String status,
      List<String> findings,
      List<String> testingRecommendations,
      List<String> nextSteps) {}

  record ApplyPatchPreviewRequest(
      String workspaceId, String patchId, List<String> commands, Boolean dryRun, String timeout) {}

  record Preview(String summary, List<String> warnings) {}

  record GradleResult(
      boolean executed, String runner, int exitCode, String status, List<String> logs) {}

  record ApplyPatchPreviewResponse(
      String patchId,
      String workspaceId,
      boolean applied,
      Preview preview,
      GradleResult gradle,
      Map<String, Object> metrics,
      Instant completedAt) {}
}
