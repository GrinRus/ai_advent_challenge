package com.aiadvent.mcp.backend.coding;

import com.aiadvent.mcp.backend.github.workspace.TempWorkspaceService;
import com.aiadvent.mcp.backend.workspace.WorkspaceFileService;
import com.aiadvent.mcp.backend.workspace.WorkspaceFileService.WorkspaceFilePayload;
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
  private final WorkspaceFileService workspaceFileService;
  private final Map<String, PatchRecord> patches = new ConcurrentHashMap<>();

  CodingAssistantService(
      TempWorkspaceService workspaceService,
      CodingAssistantProperties properties,
      WorkspaceFileService workspaceFileService) {
    this.workspaceService = Objects.requireNonNull(workspaceService, "workspaceService");
    this.properties = Objects.requireNonNull(properties, "properties");
    this.workspaceFileService =
        Objects.requireNonNull(workspaceFileService, "workspaceFileService");
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
    Workspace workspace =
        workspaceService
            .findWorkspace(workspaceId)
            .orElseThrow(() -> new IllegalArgumentException("Unknown workspaceId: " + workspaceId));

    List<String> targetPaths = normalizePaths(request.targetPaths());
    List<String> forbiddenPaths = normalizePaths(request.forbiddenPaths());
    validatePaths(targetPaths, forbiddenPaths);

    List<ContextSnippet> snippets =
        collectContext(workspaceId, normalizeContextFiles(request.contextFiles()));

    String patchId = UUID.randomUUID().toString();
    PatchRecord record =
        new PatchRecord(
            patchId,
            workspaceId,
            instructions,
            "",
            Instant.now(),
            targetPaths,
            forbiddenPaths,
            snippets);
    patches.put(patchId, record);

    Annotations annotations =
        new Annotations(
            snippets.stream().map(ContextSnippet::path).toList(),
            List.of("Patch generation stub – implement LLM call"),
            List.of());
    Usage usage = new Usage(0, 0);
    return new GeneratePatchResponse(
        patchId,
        workspaceId,
        buildSummary(record),
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

  private List<String> normalizePaths(List<String> paths) {
    if (paths == null) {
      return List.of();
    }
    return paths.stream()
        .filter(StringUtils::hasText)
        .map(String::trim)
        .distinct()
        .toList();
  }

  private List<ContextFile> normalizeContextFiles(List<ContextFile> contextFiles) {
    if (contextFiles == null) {
      return List.of();
    }
    return contextFiles.stream()
        .filter(Objects::nonNull)
        .map(
            file ->
                new ContextFile(
                    file.path() == null ? "" : file.path().trim(),
                    file.maxBytes()))
        .filter(file -> StringUtils.hasText(file.path()))
        .distinct()
        .toList();
  }

  private void validatePaths(List<String> targets, List<String> forbidden) {
    if (!targets.isEmpty() && !forbidden.isEmpty()) {
      for (String path : targets) {
        if (forbidden.contains(path)) {
          throw new IllegalArgumentException(
              "Path %s present in both targetPaths and forbiddenPaths".formatted(path));
        }
      }
    }
  }

  private List<ContextSnippet> collectContext(
      String workspaceId, List<ContextFile> contextFiles) {
    List<ContextSnippet> snippets = new ArrayList<>();
    for (ContextFile context : contextFiles) {
      WorkspaceFilePayload payload =
          workspaceFileService.readWorkspaceFile(
              workspaceId, context.path(), safeContextLimit(context.maxBytes()));
      snippets.add(
          new ContextSnippet(
              context.path(),
              payload.encoding(),
              payload.binary(),
              payload.content(),
              payload.base64Content(),
              payload.truncated()));
    }
    return snippets;
  }

  private int safeContextLimit(Integer requested) {
    int max = properties.getMaxContextBytes();
    if (requested == null || requested <= 0) {
      return max;
    }
    return Math.min(requested, max);
  }

  private String buildSummary(PatchRecord record) {
    if (record.contextSnippets().isEmpty()) {
      return "Инструкция сохранена, генерация патча будет реализована позднее.";
    }
    return "Подготовлен контекст из файлов: "
        + record.contextSnippets().stream().map(ContextSnippet::path).toList();
  }

  private record PatchRecord(
      String patchId,
      String workspaceId,
      String instructions,
      String diff,
      Instant createdAt,
      List<String> targetPaths,
      List<String> forbiddenPaths,
      List<ContextSnippet> contextSnippets) {}

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

  record ContextSnippet(
      String path, String encoding, boolean binary, String content, String base64, boolean truncated) {}

  record ApplyPatchPreviewResponse(
      String patchId,
      String workspaceId,
      boolean applied,
      Preview preview,
      GradleResult gradle,
      Map<String, Object> metrics,
      Instant completedAt) {}
}
