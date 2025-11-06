package com.aiadvent.mcp.backend.coding;

import com.aiadvent.mcp.backend.github.workspace.TempWorkspaceService;
import com.aiadvent.mcp.backend.workspace.WorkspaceFileService;
import com.aiadvent.mcp.backend.workspace.WorkspaceFileService.WorkspaceFilePayload;
import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
class CodingAssistantService {

  private static final Pattern DIFF_FILE_PATTERN =
      Pattern.compile("^diff --git a/(.+) b/(.+)$", Pattern.MULTILINE);
  private static final Pattern BINARY_FILES_PATTERN =
      Pattern.compile("^Binary files .+ differ$", Pattern.MULTILINE);
  private static final Pattern LITERAL_PATTERN =
      Pattern.compile("^literal \\d+$", Pattern.MULTILINE);

  private final TempWorkspaceService workspaceService;
  private final CodingAssistantProperties properties;
  private final WorkspaceFileService workspaceFileService;
  private final PatchRegistry patchRegistry;
  private final PatchGenerationService patchGenerationService;

  CodingAssistantService(
      TempWorkspaceService workspaceService,
      CodingAssistantProperties properties,
      WorkspaceFileService workspaceFileService,
      PatchRegistry patchRegistry,
      PatchGenerationService patchGenerationService) {
    this.workspaceService = Objects.requireNonNull(workspaceService, "workspaceService");
    this.properties = Objects.requireNonNull(properties, "properties");
    this.workspaceFileService =
        Objects.requireNonNull(workspaceFileService, "workspaceFileService");
    this.patchRegistry = Objects.requireNonNull(patchRegistry, "patchRegistry");
    this.patchGenerationService =
        Objects.requireNonNull(patchGenerationService, "patchGenerationService");
  }

  GeneratePatchResponse generatePatch(GeneratePatchRequest request) {
    Objects.requireNonNull(request, "request");
    String workspaceId = sanitizeWorkspaceId(request.workspaceId());
    String instructions =
        Optional.ofNullable(request.instructions()).map(String::trim).orElse("");
    if (!StringUtils.hasText(instructions)) {
      throw new IllegalArgumentException("instructions must not be blank");
    }
    validateInstructionLength(instructions);

    workspaceService
        .findWorkspace(workspaceId)
        .orElseThrow(() -> new IllegalArgumentException("Unknown workspaceId: " + workspaceId));

    List<String> targetPaths = normalizePaths(request.targetPaths());
    validatePathList(targetPaths, "targetPaths");
    List<String> forbiddenPaths = normalizePaths(request.forbiddenPaths());
    validatePathList(forbiddenPaths, "forbiddenPaths");
    ensureNoOverlap(targetPaths, forbiddenPaths);

    List<ContextFile> normalizedContext = normalizeContextFiles(request.contextFiles());
    List<ContextSnippet> snippets = collectContext(workspaceId, normalizedContext);

    String patchId = UUID.randomUUID().toString();
    PatchGenerationService.GenerationResult generation =
        patchGenerationService.generate(
            new PatchGenerationService.GeneratePatchCommand(
                patchId, workspaceId, instructions, targetPaths, forbiddenPaths, snippets));

    Set<String> diffFiles = validateDiff(generation.diff());
    PatchAnnotations annotations = normalizeAnnotations(generation.annotations(), diffFiles);

    CodingPatch patch =
        patchRegistry.register(
            new PatchRegistry.NewPatch(
                patchId,
                workspaceId,
                instructions,
                generation.summary(),
                generation.diff(),
                annotations,
                generation.usage() == null ? PatchUsage.empty() : generation.usage(),
                generation.requiresManualReview(),
                false,
                targetPaths,
                forbiddenPaths,
                snippets));

    return new GeneratePatchResponse(
        patch.patchId(),
        patch.workspaceId(),
        patch.summary(),
        patch.diff(),
        annotations,
        patch.usage(),
        patch.createdAt());
  }

  ReviewPatchResponse reviewPatch(ReviewPatchRequest request) {
    Objects.requireNonNull(request, "request");
    CodingPatch patch = requirePatch(request.workspaceId(), request.patchId());
    if (!StringUtils.hasText(patch.diff())) {
      List<String> findings = List.of("Патч не содержит diff — ничего не было сгенерировано.");
      List<String> recommendations = List.of("Перегенерировать патч перед ревью.");
      List<String> nextSteps = List.of("Повторно вызвать generate_patch и затем review_patch.");
      return new ReviewPatchResponse(
          patch.patchId(), "blocked", findings, recommendations, nextSteps);
    }

    Set<String> diffFiles = extractDiffFiles(patch.diff());
    EnumSet<ReviewFocus> focuses = resolveFocus(request.focus());

    List<String> findings = new ArrayList<>();
    List<String> testingRecommendations = new ArrayList<>();
    List<String> nextSteps = new ArrayList<>();

    if (focuses.contains(ReviewFocus.RISKS)) {
      analyzeRisks(patch, diffFiles, findings, nextSteps);
    }
    if (focuses.contains(ReviewFocus.TESTS)) {
      analyzeTests(patch, diffFiles, findings, testingRecommendations, nextSteps);
    }
    if (focuses.contains(ReviewFocus.MIGRATION)) {
      analyzeMigrations(diffFiles, findings, nextSteps);
    }

    if (findings.isEmpty()) {
      findings.add("Замечания не обнаружены. Патч выглядит безопасным.");
      nextSteps.add("Можно переходить к dry-run или публикации.");
    }

    boolean hasWarnings = findings.stream().anyMatch(msg -> !msg.startsWith("Замечания не обнаружены"));
    String status = hasWarnings ? "warnings" : "ok";

    patchRegistry.update(
        patch.workspaceId(),
        patch.patchId(),
        existing -> existing.withRequiresManualReview(hasWarnings));

    if (testingRecommendations.isEmpty() && focuses.contains(ReviewFocus.TESTS)) {
      testingRecommendations.add("Запустить базовые тесты проекта после применения патча.");
    }

    if (nextSteps.isEmpty()) {
      nextSteps.add("Подтвердить dry-run и подготовку к публикации.");
    }

    return new ReviewPatchResponse(
        patch.patchId(), status, findings, testingRecommendations, nextSteps);
  }

  ApplyPatchPreviewResponse applyPatchPreview(ApplyPatchPreviewRequest request) {
    Objects.requireNonNull(request, "request");
    CodingPatch patch = requirePatch(request.workspaceId(), request.patchId());
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
        patch.patchId(),
        patch.workspaceId(),
        false,
        preview,
        gradle,
        Map.of("status", "stub"),
        Instant.now());
  }

  private CodingPatch requirePatch(String workspaceId, String patchId) {
    String sanitizedWorkspaceId = sanitizeWorkspaceId(workspaceId);
    if (!StringUtils.hasText(patchId)) {
      throw new IllegalArgumentException("patchId must not be blank");
    }
    return patchRegistry.get(sanitizedWorkspaceId, patchId);
  }

  private String sanitizeWorkspaceId(String workspaceId) {
    String sanitized =
        Optional.ofNullable(workspaceId).map(String::trim).orElse("");
    if (!StringUtils.hasText(sanitized)) {
      throw new IllegalArgumentException("workspaceId must not be blank");
    }
    return sanitized;
  }

  private void validateInstructionLength(String instructions) {
    int max = properties.getMaxInstructionLength();
    if (instructions.length() > max) {
      throw new IllegalArgumentException(
          "instructions exceeds max length of " + max + " characters");
    }
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

  private void validatePathList(List<String> paths, String fieldName) {
    for (String path : paths) {
      validateRelativePath(path, fieldName);
    }
  }

  private void ensureNoOverlap(List<String> targets, List<String> forbidden) {
    if (!targets.isEmpty() && !forbidden.isEmpty()) {
      for (String path : targets) {
        if (forbidden.contains(path)) {
          throw new IllegalArgumentException(
              "Path %s present in both targetPaths and forbiddenPaths".formatted(path));
        }
      }
    }
  }

  private List<ContextFile> normalizeContextFiles(List<ContextFile> contextFiles) {
    if (contextFiles == null) {
      return List.of();
    }
    LinkedHashSet<ContextFile> sanitized = new LinkedHashSet<>();
    for (ContextFile file : contextFiles) {
      if (file == null) {
        continue;
      }
      String path = file.path() == null ? "" : file.path().trim();
      if (!StringUtils.hasText(path)) {
        continue;
      }
      validateRelativePath(path, "contextFiles");
      sanitized.add(new ContextFile(path, file.maxBytes()));
    }
    return List.copyOf(sanitized);
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

  private void validateRelativePath(String path, String fieldName) {
    if (!StringUtils.hasText(path)) {
      throw new IllegalArgumentException(fieldName + " must not contain blank values");
    }
    if (path.indexOf('\0') >= 0) {
      throw new IllegalArgumentException(fieldName + " contains illegal character: " + path);
    }
    String normalizedSlashes = path.replace('\\', '/');
    if (normalizedSlashes.startsWith("/")) {
      throw new IllegalArgumentException(
          "%s must be relative, got absolute path: %s".formatted(fieldName, path));
    }
    if (normalizedSlashes.startsWith("../")
        || normalizedSlashes.contains("/../")
        || normalizedSlashes.equals("..")) {
      throw new IllegalArgumentException(
          "%s must not traverse outside workspace: %s".formatted(fieldName, path));
    }
    if (normalizedSlashes.matches("^[A-Za-z]:.*")) {
      throw new IllegalArgumentException(
          "%s must be relative, got platform-specific absolute path: %s"
              .formatted(fieldName, path));
    }
    try {
      Path candidate = Path.of(path).normalize();
      if (candidate.isAbsolute() || candidate.toString().equals("..")) {
        throw new IllegalArgumentException(
            "%s must not resolve outside workspace: %s".formatted(fieldName, path));
      }
      String candidateStr = candidate.toString().replace('\\', '/');
      if (candidateStr.startsWith("../") || candidateStr.contains("/../")) {
        throw new IllegalArgumentException(
            "%s must not resolve outside workspace: %s".formatted(fieldName, path));
      }
    } catch (InvalidPathException ex) {
      throw new IllegalArgumentException(
          "%s contains invalid path component: %s".formatted(fieldName, path), ex);
    }
  }

  private Set<String> validateDiff(String diff) {
    if (!StringUtils.hasText(diff)) {
      return Set.of();
    }
    byte[] diffBytes = diff.getBytes(StandardCharsets.UTF_8);
    if (diffBytes.length > properties.getMaxDiffBytes()) {
      throw new IllegalArgumentException(
          "Generated diff exceeds maxDiffBytes limit: "
              + diffBytes.length
              + " > "
              + properties.getMaxDiffBytes());
    }
    if (containsBinaryPatch(diff)) {
      throw new IllegalArgumentException("Generated diff must not contain binary patches");
    }
    Set<String> diffFiles = extractDiffFiles(diff);
    if (diffFiles.size() > properties.getMaxFilesPerPatch()) {
      throw new IllegalArgumentException(
          "Generated diff touches too many files: "
              + diffFiles.size()
              + " > "
              + properties.getMaxFilesPerPatch());
    }
    diffFiles.forEach(path -> validateRelativePath(path, "diff files"));
    return diffFiles;
  }

  private boolean containsBinaryPatch(String diff) {
    return diff.contains("GIT binary patch")
        || BINARY_FILES_PATTERN.matcher(diff).find()
        || LITERAL_PATTERN.matcher(diff).find();
  }

  private Set<String> extractDiffFiles(String diff) {
    Set<String> files = new LinkedHashSet<>();
    Matcher matcher = DIFF_FILE_PATTERN.matcher(diff);
    while (matcher.find()) {
      String aPath = matcher.group(1);
      String bPath = matcher.group(2);
      String candidate =
          "/dev/null".equals(bPath)
              ? aPath
              : ("/dev/null".equals(aPath) ? bPath : bPath);
      if (StringUtils.hasText(candidate)) {
        files.add(candidate.trim());
      }
    }
    return files;
  }

  private PatchAnnotations normalizeAnnotations(
      PatchAnnotations annotations, Set<String> diffFiles) {
    List<String> files =
        annotations == null
            ? new ArrayList<>()
            : new ArrayList<>(annotations.files());
    diffFiles.stream().filter(path -> !files.contains(path)).forEach(files::add);

    List<String> risks =
        annotations == null ? new ArrayList<>() : new ArrayList<>(annotations.risks());
    List<String> conflicts =
        annotations == null ? new ArrayList<>() : new ArrayList<>(annotations.conflicts());

    return new PatchAnnotations(files, risks, conflicts);
  }

  private EnumSet<ReviewFocus> resolveFocus(List<String> focus) {
    if (focus == null || focus.isEmpty()) {
      return EnumSet.allOf(ReviewFocus.class);
    }
    EnumSet<ReviewFocus> resolved = EnumSet.noneOf(ReviewFocus.class);
    for (String value : focus) {
      if (!StringUtils.hasText(value)) {
        continue;
      }
      try {
        resolved.add(ReviewFocus.valueOf(value.trim().toUpperCase()));
      } catch (IllegalArgumentException ex) {
        // ignore unknown focus values
      }
    }
    if (resolved.isEmpty()) {
      return EnumSet.allOf(ReviewFocus.class);
    }
    return resolved;
  }

  private void analyzeRisks(
      CodingPatch patch, Set<String> diffFiles, List<String> findings, List<String> nextSteps) {
    if (diffFiles.stream().anyMatch(this::isBuildFile)) {
      findings.add("Изменяются build/config файлы — требуется ручная проверка зависимостей.");
      nextSteps.add("Проверить сборку и прогнать smoke-тесты после применения.");
    }
    if (diffFiles.stream().anyMatch(path -> path.contains("Security") || path.contains("Auth"))) {
      findings.add("Затронуты security/auth файлы — убедитесь в корректности права доступа.");
      nextSteps.add("Планируется ручной аудит изменений безопасности.");
    }
    if (patch.diff().contains("TODO") || patch.diff().contains("FIXME")) {
      findings.add("Diff содержит TODO/FIXME — возможно, требуется доработка перед merge.");
      nextSteps.add("Удалить временные пометки и задокументировать решения.");
    }
  }

  private void analyzeTests(
      CodingPatch patch,
      Set<String> diffFiles,
      List<String> findings,
      List<String> testingRecommendations,
      List<String> nextSteps) {
    boolean touchesTests = diffFiles.stream().anyMatch(this::isTestFile);
    if (!touchesTests) {
      findings.add("Не обнаружены изменения в тестах — стоит добавить покрытие.");
      testingRecommendations.add("Добавить тесты, покрывающие изменения.");
      nextSteps.add("Подготовить и запустить релевантные unit/integration тесты.");
    }
    if (patch.diff().contains("@Transactional") || patch.diff().contains("database")) {
      testingRecommendations.add("Выполнить интеграционные тесты с БД.");
    }
  }

  private void analyzeMigrations(Set<String> diffFiles, List<String> findings, List<String> nextSteps) {
    boolean touchesMigrations =
        diffFiles.stream()
            .anyMatch(path -> path.toLowerCase().contains("migration") || path.contains("db/migrate"));
    if (touchesMigrations) {
      findings.add("Изменения затрагивают миграции — требуется проверить порядок применения.");
      nextSteps.add("Согласовать миграции и выполнить dry-run базы данных.");
    }
  }

  private boolean isBuildFile(String path) {
    String lower = path.toLowerCase();
    return lower.endsWith("pom.xml")
        || lower.endsWith("build.gradle")
        || lower.endsWith("build.gradle.kts")
        || lower.contains("gradle.properties")
        || lower.contains("settings.gradle")
        || lower.contains("package.json")
        || lower.contains("requirements.txt")
        || lower.contains("pyproject.toml");
  }

  private boolean isTestFile(String path) {
    String lower = path.toLowerCase();
    return lower.contains("/test")
        || lower.contains("/tests")
        || lower.contains("__tests__")
        || lower.endsWith("test.java")
        || lower.endsWith("tests.java")
        || lower.endsWith("spec.ts")
        || lower.endsWith("spec.js")
        || lower.endsWith("test.ts")
        || lower.endsWith("test.js")
        || lower.endsWith("test.py");
  }

  private enum ReviewFocus {
    RISKS,
    TESTS,
    MIGRATION
  }

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
      PatchAnnotations annotations,
      PatchUsage usage,
      Instant createdAt) {}

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
