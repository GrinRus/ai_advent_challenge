package com.aiadvent.mcp.backend.coding;

import com.aiadvent.mcp.backend.docker.DockerRunnerService;
import com.aiadvent.mcp.backend.docker.DockerRunnerService.DockerGradleRunInput;
import com.aiadvent.mcp.backend.docker.DockerRunnerService.DockerGradleRunResult;
import com.aiadvent.mcp.backend.docker.DockerRunnerService.DockerRunnerException;
import com.aiadvent.mcp.backend.github.workspace.TempWorkspaceService;
import com.aiadvent.mcp.backend.workspace.WorkspaceFileService;
import com.aiadvent.mcp.backend.workspace.WorkspaceFileService.WorkspaceFilePayload;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.lang.Nullable;

@Service
class CodingAssistantService {

  private static final Logger log = LoggerFactory.getLogger(CodingAssistantService.class);
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
  private final DockerRunnerService dockerRunnerService;
  private final MeterRegistry meterRegistry;
  private final Counter patchAttemptCounter;
  private final Counter patchSuccessCounter;
  private final Counter patchCompileFailCounter;

  CodingAssistantService(
      TempWorkspaceService workspaceService,
      CodingAssistantProperties properties,
      WorkspaceFileService workspaceFileService,
      PatchRegistry patchRegistry,
      PatchGenerationService patchGenerationService,
      DockerRunnerService dockerRunnerService,
      @Nullable MeterRegistry meterRegistry) {
    this.workspaceService = Objects.requireNonNull(workspaceService, "workspaceService");
    this.properties = Objects.requireNonNull(properties, "properties");
    this.workspaceFileService =
        Objects.requireNonNull(workspaceFileService, "workspaceFileService");
    this.patchRegistry = Objects.requireNonNull(patchRegistry, "patchRegistry");
    this.patchGenerationService =
        Objects.requireNonNull(patchGenerationService, "patchGenerationService");
    this.dockerRunnerService =
        Objects.requireNonNull(dockerRunnerService, "dockerRunnerService");
    MeterRegistry registry = meterRegistry;
    if (registry == null) {
      registry = new SimpleMeterRegistry();
    }
    this.meterRegistry = registry;
    this.patchAttemptCounter = this.meterRegistry.counter("coding_patch_attempt_total");
    this.patchSuccessCounter = this.meterRegistry.counter("coding_patch_success_total");
    this.patchCompileFailCounter =
        this.meterRegistry.counter("coding_patch_compile_fail_total");
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
          patch.patchId(),
          "blocked",
          findings,
          recommendations,
          nextSteps,
          patch.annotations(),
          patch.usage());
    }

    Set<String> diffFiles = extractDiffFiles(patch.diff());
    EnumSet<ReviewFocus> focuses = resolveFocus(request.focus());

    List<String> findings = new ArrayList<>();
    List<String> testingRecommendations = new ArrayList<>();
    List<String> nextSteps = new ArrayList<>();
    List<String> riskAnnotations = new ArrayList<>();

    if (focuses.contains(ReviewFocus.RISKS)) {
      analyzeRisks(patch, diffFiles, findings, riskAnnotations, nextSteps);
    }
    if (focuses.contains(ReviewFocus.TESTS)) {
      analyzeTests(patch, diffFiles, findings, testingRecommendations, riskAnnotations, nextSteps);
    }
    if (focuses.contains(ReviewFocus.MIGRATION)) {
      analyzeMigrations(diffFiles, findings, riskAnnotations, nextSteps);
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

    PatchAnnotations annotations =
        new PatchAnnotations(new ArrayList<>(diffFiles), riskAnnotations, List.of());

    return new ReviewPatchResponse(
        patch.patchId(), status, findings, testingRecommendations, nextSteps, annotations, patch.usage());
  }

  ApplyPatchPreviewResponse applyPatchPreview(ApplyPatchPreviewRequest request) {
    Objects.requireNonNull(request, "request");
    CodingPatch patch = requirePatch(request.workspaceId(), request.patchId());
    boolean dryRun = request.dryRun() == null ? true : request.dryRun();
    if (!StringUtils.hasText(patch.diff())) {
      throw new IllegalStateException("Patch diff is empty — nothing to apply");
    }
    patchAttemptCounter.increment();

    TempWorkspaceService.Workspace workspace =
        workspaceService
            .findWorkspace(patch.workspaceId())
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "Unknown workspaceId: " + patch.workspaceId()));

    Path workspacePath = workspace.path();

    byte[] diffBytes = patch.diff().getBytes(StandardCharsets.UTF_8);
    GitResult checkResult =
        runGitCommand(
            workspacePath,
            diffBytes,
            Duration.ofMinutes(1),
            "git",
            "apply",
            "--check",
            "--whitespace=nowarn",
            "-");
    List<String> warnings = new ArrayList<>();
    if (checkResult.exitCode() != 0) {
      warnings.add("git apply --check завершился с ошибкой: " + checkResult.stderr());
      PatchAnnotations failureAnnotations =
          new PatchAnnotations(List.of(), List.of("dry-run aborted"), List.of(checkResult.stderr()));
      return new ApplyPatchPreviewResponse(
          patch.patchId(),
          patch.workspaceId(),
          false,
          new Preview("Патч не применён: обнаружены конфликты.", warnings, "failed", List.of(), List.of()),
          new GradleResult(false, null, 0, "not_executed", List.of()),
          failureAnnotations,
          patch.usage(),
          Map.of("gitApplyCheck", "failed"),
          Instant.now());
    }

    boolean applied = false;
    String diffStat = "";
    String diffNameStatus = "";
    GradleResult gradleResult = new GradleResult(false, null, 0, "not_executed", List.of());
    Map<String, Object> metrics = new java.util.LinkedHashMap<>();
    metrics.put("gitApplyCheck", "success");

    Instant started = Instant.now();
    try {
      if (dryRun) {
        GitResult applyResult =
            runGitCommand(
                workspacePath,
                diffBytes,
                Duration.ofMinutes(2),
                "git",
                "apply",
                "--whitespace=nowarn",
                "-");
        if (applyResult.exitCode() != 0) {
          warnings.add("git apply завершился с ошибкой: " + applyResult.stderr());
          PatchAnnotations errorAnnotations =
              new PatchAnnotations(
                  List.of(), List.of("dry-run aborted"), List.of(applyResult.stderr()));
          return new ApplyPatchPreviewResponse(
              patch.patchId(),
              patch.workspaceId(),
              false,
              new Preview(
                  "Патч не применён: git apply завершился с ошибкой.",
                  warnings,
                  "failed",
                  List.of(),
                  List.of("Исправьте конфликт и повторите dry-run.")),
              gradleResult,
              errorAnnotations,
              patch.usage(),
              metrics,
              Instant.now());
        }
        applied = true;
        diffStat =
            runGitCommand(
                    workspacePath, null, Duration.ofSeconds(30), "git", "diff", "--stat")
                .stdout();
        diffNameStatus =
            runGitCommand(
                    workspacePath, null, Duration.ofSeconds(30), "git", "diff", "--name-status")
                .stdout();
        metrics.put("filesTouched", extractFilesTouched(diffNameStatus));

        if (request.commands() != null
            && !request.commands().isEmpty()
            && !request.commands().stream().allMatch(String::isBlank)) {
          gradleResult =
              executeDryRunCommands(
                  patch.workspaceId(), request.commands(), request.timeout(), warnings, metrics);
          if (!gradleResult.executed() || gradleResult.exitCode() != 0) {
            patchCompileFailCounter.increment();
          }
        }
        patchSuccessCounter.increment();
        patchRegistry.markDryRun(patch.workspaceId(), patch.patchId(), true);
      } else {
        warnings.add("Dry-run отключён в запросе — git apply не выполнялся.");
        metrics.put("dryRun", "skipped");
      }
    } finally {
      if (applied) {
        runGitCommand(
            workspacePath,
            diffBytes,
            Duration.ofMinutes(1),
            "git",
            "apply",
            "--reverse",
            "--whitespace=nowarn",
            "-");
      }
    }

    List<String> previewWarnings = new ArrayList<>(warnings);
    List<String> modifiedFiles = extractFilesTouched(diffNameStatus);
    String previewSummary =
        dryRun
            ? (StringUtils.hasText(diffStat)
                ? "Патч применён в preview. Статистика:\n" + diffStat.strip()
                : "Патч применён в preview.")
            : "Dry-run пропущен по запросу.";
    String dryRunStatus =
        dryRun
            ? (gradleResult.executed() && gradleResult.exitCode() == 0 ? "applied" : "applied_with_warnings")
            : "skipped";
    List<String> recommendations = new ArrayList<>();
    if (dryRun && gradleResult.executed() && gradleResult.exitCode() == 0 && warnings.isEmpty()) {
      recommendations.add("Можно переходить к публикации изменений.");
    } else if (dryRun && (!warnings.isEmpty() || gradleResult.exitCode() != 0)) {
      recommendations.add("Проанализировать предупреждения и повторить dry-run после исправлений.");
    } else {
      recommendations.add("Рассмотреть запуск dry-run перед публикацией.");
    }
    Preview preview =
        new Preview(previewSummary, previewWarnings, dryRunStatus, modifiedFiles, recommendations);

    metrics.put("durationMs", Duration.between(started, Instant.now()).toMillis());

    log.info(
        "coding.apply_patch_preview completed: patchId={}, workspaceId={}, dryRun={}, gitWarnings={}, gradleStatus={}",
        patch.patchId(),
        patch.workspaceId(),
        dryRun,
        !warnings.isEmpty(),
        gradleResult.status());

    List<String> riskHints = new ArrayList<>();
    if (!warnings.isEmpty()) {
      riskHints.addAll(warnings);
    }
    if (gradleResult.executed() && gradleResult.exitCode() != 0) {
      riskHints.add("Docker runner вернул код " + gradleResult.exitCode());
    }
    PatchAnnotations previewAnnotations =
        new PatchAnnotations(modifiedFiles, riskHints, dryRun ? List.of() : List.of("dry-run skipped"));

    return new ApplyPatchPreviewResponse(
        patch.patchId(),
        patch.workspaceId(),
        dryRun && warnings.isEmpty(),
        preview,
        gradleResult,
        previewAnnotations,
        patch.usage(),
        metrics,
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

  private GradleResult executeDryRunCommands(
      String workspaceId,
      List<String> commands,
      String timeout,
      List<String> warnings,
      Map<String, Object> metrics) {
    Queue<String> queue = new ArrayDeque<>();
    commands.stream().filter(StringUtils::hasText).map(String::trim).forEach(queue::add);
    if (queue.isEmpty()) {
      return new GradleResult(false, null, 0, "not_executed", List.of());
    }
    String command = queue.poll();
    List<String> tokens = List.of(command.split("\\s+"));
    if (tokens.isEmpty()) {
      return new GradleResult(false, null, 0, "not_executed", List.of());
    }

    String runner = tokens.get(0);
    if (!runner.equals("./gradlew") && !runner.equals("gradle")) {
      warnings.add("Команда \"" + command + "\" не поддерживается в preview и была пропущена.");
      return new GradleResult(false, null, 0, "not_supported", List.of());
    }

    List<String> tasks = tokens.subList(1, tokens.size());
    if (tasks.isEmpty()) {
      warnings.add("Команда Gradle не содержит задач — пропуск запуска.");
      return new GradleResult(false, runner, 0, "not_executed", List.of());
    }

    Duration timeoutDuration = parseTimeout(timeout, Duration.ofMinutes(10));
    DockerGradleRunInput input =
        new DockerGradleRunInput(
            workspaceId, null, tasks, List.of(), Map.of(), timeoutDuration);
    try {
      DockerGradleRunResult result = dockerRunnerService.runGradle(input);
      metrics.put("dockerCommand", result.dockerCommand());
      metrics.put("dockerDurationMs", result.duration().toMillis());
      return new GradleResult(
          true,
          result.runnerExecutable(),
          result.exitCode(),
          result.status(),
          mergeLogs(result.stdout(), result.stderr()));
    } catch (DockerRunnerException ex) {
      warnings.add("Запуск Docker runner завершился ошибкой: " + ex.getMessage());
      patchCompileFailCounter.increment();
      return new GradleResult(false, runner, -1, "failed", List.of());
    }
  }

  private Duration parseTimeout(String timeout, Duration defaultValue) {
    if (!StringUtils.hasText(timeout)) {
      return defaultValue;
    }
    try {
      return Duration.parse(timeout.trim());
    } catch (Exception ex) {
      return defaultValue;
    }
  }

  private List<String> mergeLogs(List<String> stdout, List<String> stderr) {
    List<String> merged = new ArrayList<>();
    if (stdout != null) {
      merged.addAll(stdout);
    }
    if (stderr != null && !stderr.isEmpty()) {
      merged.add("--- stderr ---");
      merged.addAll(stderr);
    }
    return merged.isEmpty() ? List.of() : merged;
  }

  private List<String> extractFilesTouched(String diffNameStatus) {
    if (!StringUtils.hasText(diffNameStatus)) {
      return List.of();
    }
    return diffNameStatus.lines()
        .map(String::trim)
        .filter(line -> !line.isEmpty())
        .map(line -> line.replace("\t", " "))
        .collect(Collectors.toList());
  }

  private GitResult runGitCommand(
      Path workspacePath, byte[] stdin, Duration timeout, String... command) {
    ProcessBuilder builder = new ProcessBuilder(command);
    builder.directory(workspacePath.toFile());
    builder.redirectErrorStream(false);
    Process process;
    try {
      process = builder.start();
    } catch (IOException ex) {
      throw new IllegalStateException("Failed to start git process", ex);
    }
    if (stdin != null && stdin.length > 0) {
      try (OutputStream output = process.getOutputStream()) {
        output.write(stdin);
        output.flush();
      } catch (IOException ex) {
        process.destroyForcibly();
        throw new IllegalStateException("Failed to send data to git process", ex);
      }
    } else {
      try {
        process.getOutputStream().close();
      } catch (IOException ignored) {
      }
    }
    StreamCollector stdout = new StreamCollector();
    StreamCollector stderr = new StreamCollector();
    Thread stdoutThread = new Thread(() -> stdout.collect(process.getInputStream()));
    Thread stderrThread = new Thread(() -> stderr.collect(process.getErrorStream()));
    stdoutThread.start();
    stderrThread.start();
    boolean finished;
    try {
      finished = process.waitFor(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      process.destroyForcibly();
      throw new IllegalStateException("git command interrupted", ex);
    }
    if (!finished) {
      process.destroyForcibly();
      throw new IllegalStateException("git command timed out: " + String.join(" ", command));
    }
    try {
      stdoutThread.join();
      stderrThread.join();
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
    }
    return new GitResult(process.exitValue(), stdout.content(), stderr.content());
  }

  private static class StreamCollector {
    private final StringBuilder buffer = new StringBuilder();

    void collect(java.io.InputStream stream) {
      try (stream) {
        byte[] data = stream.readAllBytes();
        buffer.append(new String(data, StandardCharsets.UTF_8));
      } catch (IOException ex) {
        buffer.append(ex.getMessage());
      }
    }

    String content() {
      return buffer.toString();
    }
  }

  private record GitResult(int exitCode, String stdout, String stderr) {}

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
      CodingPatch patch,
      Set<String> diffFiles,
      List<String> findings,
      List<String> riskAnnotations,
      List<String> nextSteps) {
    if (diffFiles.stream().anyMatch(this::isBuildFile)) {
      String message = "Изменяются build/config файлы — требуется ручная проверка зависимостей.";
      findings.add(message);
      riskAnnotations.add(message);
      nextSteps.add("Проверить сборку и прогнать smoke-тесты после применения.");
    }
    if (diffFiles.stream().anyMatch(path -> path.contains("Security") || path.contains("Auth"))) {
      String message = "Затронуты security/auth файлы — убедитесь в корректности права доступа.";
      findings.add(message);
      riskAnnotations.add(message);
      nextSteps.add("Планируется ручной аудит изменений безопасности.");
    }
    if (patch.diff().contains("TODO") || patch.diff().contains("FIXME")) {
      String message = "Diff содержит TODO/FIXME — возможно, требуется доработка перед merge.";
      findings.add(message);
      riskAnnotations.add(message);
      nextSteps.add("Удалить временные пометки и задокументировать решения.");
    }
  }

  private void analyzeTests(
      CodingPatch patch,
      Set<String> diffFiles,
      List<String> findings,
      List<String> testingRecommendations,
      List<String> riskAnnotations,
      List<String> nextSteps) {
    boolean touchesTests = diffFiles.stream().anyMatch(this::isTestFile);
    if (!touchesTests) {
      String message = "Не обнаружены изменения в тестах — стоит добавить покрытие.";
      findings.add(message);
      riskAnnotations.add(message);
      testingRecommendations.add("Добавить тесты, покрывающие изменения.");
      nextSteps.add("Подготовить и запустить релевантные unit/integration тесты.");
    }
    if (patch.diff().contains("@Transactional") || patch.diff().contains("database")) {
      testingRecommendations.add("Выполнить интеграционные тесты с БД.");
    }
  }

  private void analyzeMigrations(
      Set<String> diffFiles,
      List<String> findings,
      List<String> riskAnnotations,
      List<String> nextSteps) {
    boolean touchesMigrations =
        diffFiles.stream()
            .anyMatch(path -> path.toLowerCase().contains("migration") || path.contains("db/migrate"));
    if (touchesMigrations) {
      String message = "Изменения затрагивают миграции — требуется проверить порядок применения.";
      findings.add(message);
      riskAnnotations.add(message);
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
      List<String> nextSteps,
      PatchAnnotations annotations,
      PatchUsage usage) {}

  record ApplyPatchPreviewRequest(
      String workspaceId, String patchId, List<String> commands, Boolean dryRun, String timeout) {}

  record Preview(
      String summary,
      List<String> warnings,
      String dryRunStatus,
      List<String> modifiedFiles,
      List<String> recommendations) {}

  record GradleResult(
      boolean executed, String runner, int exitCode, String status, List<String> logs) {}

  record ApplyPatchPreviewResponse(
      String patchId,
      String workspaceId,
      boolean applied,
      Preview preview,
      GradleResult gradle,
      PatchAnnotations annotations,
      PatchUsage usage,
      Map<String, Object> metrics,
      Instant completedAt) {}
}
