package com.aiadvent.mcp.backend.docker;

import com.aiadvent.mcp.backend.config.DockerRunnerProperties;
import com.aiadvent.mcp.backend.github.workspace.TempWorkspaceService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class DockerRunnerService {

  private static final Logger log = LoggerFactory.getLogger(DockerRunnerService.class);
  private static final DateTimeFormatter ARTIFACT_ID_FORMATTER =
      DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault());

  private final DockerRunnerProperties properties;
  private final TempWorkspaceService workspaceService;
  private final MeterRegistry meterRegistry;
  private final Timer runTimer;
  private final Counter runSuccessCounter;
  private final Counter runFailureCounter;
  private final DistributionSummary runDurationSummary;
  private final Counter fallbackCounter;

  public DockerRunnerService(
      DockerRunnerProperties properties,
      TempWorkspaceService workspaceService,
      @Nullable MeterRegistry meterRegistry) {
    this.properties = Objects.requireNonNull(properties, "properties");
    this.workspaceService = Objects.requireNonNull(workspaceService, "workspaceService");
    MeterRegistry registry = meterRegistry;
    if (registry == null) {
      registry = new SimpleMeterRegistry();
    }
    this.meterRegistry = registry;
    this.runTimer = this.meterRegistry.timer("docker_gradle_runner_duration");
    this.runSuccessCounter = this.meterRegistry.counter("docker_gradle_runner_success_total");
    this.runFailureCounter = this.meterRegistry.counter("docker_gradle_runner_failure_total");
    this.runDurationSummary =
        this.meterRegistry.summary("docker_gradle_runner_duration_ms");
    this.fallbackCounter = this.meterRegistry.counter("docker_runner_fallback_total");
  }

  public DockerGradleRunResult runGradle(DockerGradleRunInput input) {
    DockerBuildRunResult result =
        runBuild(
            new DockerBuildRunInput(
                input.workspaceId(),
                input.projectPath(),
                RunnerProfile.GRADLE,
                input.tasks(),
                input.arguments(),
                input.env(),
                input.timeout(),
                null,
                false));

    return new DockerGradleRunResult(
        result.workspaceId(),
        result.projectPath(),
        result.runnerExecutable(),
        result.dockerCommand(),
        result.exitCode(),
        result.status(),
        result.stdout(),
        result.stderr(),
        result.duration(),
        result.startedAt(),
        result.finishedAt());
  }

  public DockerBuildRunResult runBuild(DockerBuildRunInput input) {
    Objects.requireNonNull(input, "input");
    String workspaceId =
        Optional.ofNullable(input.workspaceId()).map(String::trim).orElse("");
    if (!StringUtils.hasText(workspaceId)) {
      throw new IllegalArgumentException("workspaceId must not be blank");
    }

    TempWorkspaceService.Workspace workspace =
        workspaceService.findWorkspace(workspaceId).orElseThrow(
            () -> new IllegalArgumentException("Unknown workspaceId: " + workspaceId));
    Path workspacePath = workspace.path();
    validateWorkspacePath(workspacePath);

    String projectPath =
        Optional.ofNullable(input.projectPath()).map(String::trim).filter(s -> !s.isEmpty()).orElse(null);
    Path projectAbsolute = resolveProjectPath(workspacePath, projectPath);
    RunnerProfile requestedProfile =
        input.profile() != null ? input.profile() : RunnerProfile.AUTO;

    MountConfiguration mountConfig = prepareMountConfiguration(workspace);
    Path containerWorkspaceRoot = mountConfig.containerWorkspaceRoot();
    Path containerProjectPath =
        StringUtils.hasText(projectPath)
            ? containerWorkspaceRoot.resolve(projectPath)
            : containerWorkspaceRoot;

    RunnerProfile detectedProfile =
        requestedProfile == RunnerProfile.AUTO
            ? Optional.ofNullable(detectProfile(projectAbsolute)).orElse(RunnerProfile.AUTO)
            : requestedProfile;

    List<BuildExecutionPlan> plans = new ArrayList<>();
    BuildExecutionPlan primary =
        createExecutionPlan(
            detectedProfile,
            workspacePath,
            projectAbsolute,
            containerWorkspaceRoot,
            containerProjectPath,
            projectPath,
            input.tasks(),
            input.arguments());
    if (primary != null) {
      plans.add(primary);
    }
    if ((primary == null || detectedProfile == RunnerProfile.AUTO)
        && Boolean.TRUE.equals(input.enableFallback())) {
      plans.addAll(buildFallbackPlans(containerProjectPath));
    }
    if (plans.isEmpty()) {
      throw new IllegalArgumentException("Unable to determine build profile or fallback commands");
    }

    DockerBuildRunResult lastResult = null;
    for (BuildExecutionPlan plan : plans) {
      DockerBuildRunResult attempt =
          executePlan(
              plan,
              mountConfig,
              workspace,
              normalizeRequestId(workspace.requestId()),
              projectPath,
              input.env(),
              input.timeout(),
              input.artifactId());
      lastResult = attempt;
      if (attempt.exitCode() == 0) {
        return attempt;
      }
      if (Boolean.TRUE.equals(input.enableFallback())) {
        fallbackCounter.increment();
      }
    }
    return lastResult;
  }

  private RunnerProfile detectProfile(Path projectAbsolute) {
    if (projectAbsolute == null) {
      return null;
    }
    try {
      if (Files.exists(projectAbsolute.resolve("settings.gradle"))
          || Files.exists(projectAbsolute.resolve("settings.gradle.kts"))
          || Files.exists(projectAbsolute.resolve("build.gradle"))
          || Files.exists(projectAbsolute.resolve("build.gradle.kts"))) {
        return RunnerProfile.GRADLE;
      }
      if (Files.exists(projectAbsolute.resolve("pom.xml"))) {
        return RunnerProfile.MAVEN;
      }
      if (Files.exists(projectAbsolute.resolve("package.json"))) {
        return RunnerProfile.NPM;
      }
      if (Files.exists(projectAbsolute.resolve("pyproject.toml"))
          || Files.exists(projectAbsolute.resolve("requirements.txt"))
          || Files.exists(projectAbsolute.resolve("Pipfile"))) {
        return RunnerProfile.PYTHON;
      }
      if (Files.exists(projectAbsolute.resolve("go.mod"))) {
        return RunnerProfile.GO;
      }
    } catch (Exception ex) {
      log.debug("Unable to detect project profile: {}", ex.getMessage());
    }
    return null;
  }

  private BuildExecutionPlan createExecutionPlan(
      RunnerProfile profile,
      Path workspacePath,
      Path projectAbsolute,
      Path containerWorkspaceRoot,
      Path containerProjectPath,
      String projectPath,
      List<String> taskInput,
      List<String> argumentInput) {
    if (profile == RunnerProfile.AUTO) {
      return null;
    }
    List<String> tasks = sanitizeList(taskInput, profile.defaultTasks());
    List<String> arguments = sanitizeList(argumentInput, List.of());
    String workDir = containerProjectPath.toString();
    List<String> command = new ArrayList<>();
    String runnerExecutable;
    switch (profile) {
      case GRADLE -> {
        boolean hasProjectWrapper = ensureGradleWrapperExecutable(projectAbsolute.resolve("gradlew"));
        boolean hasRootWrapper = ensureGradleWrapperExecutable(workspacePath.resolve("gradlew"));
        if (hasProjectWrapper) {
          runnerExecutable = "./gradlew";
          command.add(runnerExecutable);
          if (!arguments.isEmpty()) {
            command.addAll(arguments);
          }
          command.addAll(tasks);
        } else if (hasRootWrapper) {
          runnerExecutable = "./gradlew";
          workDir = containerWorkspaceRoot.toString();
          command.add(runnerExecutable);
          if (StringUtils.hasText(projectPath)) {
            command.add("-p");
            command.add(projectPath);
          }
          if (!arguments.isEmpty()) {
            command.addAll(arguments);
          }
          command.addAll(tasks);
        } else {
          runnerExecutable = "gradle";
          command.add(runnerExecutable);
          if (StringUtils.hasText(projectPath)) {
            command.add("-p");
            command.add(projectPath);
          }
          if (!arguments.isEmpty()) {
            command.addAll(arguments);
          }
          command.addAll(tasks);
        }
        return new BuildExecutionPlan(profile, workDir, command, runnerExecutable, profile.defaultEnv());
      }
      case MAVEN -> {
        runnerExecutable = "mvn";
        command.add(runnerExecutable);
        command.add("-B");
        if (!arguments.isEmpty()) {
          command.addAll(arguments);
        }
        if (tasks.isEmpty()) {
          command.add("test");
        } else {
          command.addAll(tasks);
        }
        return new BuildExecutionPlan(profile, workDir, command, runnerExecutable, profile.defaultEnv());
      }
      case NPM -> {
        runnerExecutable = "npm";
        command.add(runnerExecutable);
        List<String> effectiveTasks = tasks.isEmpty() ? List.of("test") : tasks;
        command.addAll(effectiveTasks);
        if (!arguments.isEmpty()) {
          command.addAll(arguments);
        }
        return new BuildExecutionPlan(profile, workDir, command, runnerExecutable, profile.defaultEnv());
      }
      case PYTHON -> {
        runnerExecutable = tasks.isEmpty() ? "pytest" : tasks.get(0);
        command.add(runnerExecutable);
        if (tasks.size() > 1) {
          command.addAll(tasks.subList(1, tasks.size()));
        }
        if (!arguments.isEmpty()) {
          command.addAll(arguments);
        }
        return new BuildExecutionPlan(profile, workDir, command, runnerExecutable, profile.defaultEnv());
      }
      case GO -> {
        runnerExecutable = "go";
        command.add(runnerExecutable);
        List<String> effectiveTasks = tasks.isEmpty() ? List.of("test", "./...") : tasks;
        command.addAll(effectiveTasks);
        if (!arguments.isEmpty()) {
          command.addAll(arguments);
        }
        return new BuildExecutionPlan(profile, workDir, command, runnerExecutable, profile.defaultEnv());
      }
      default -> {
        return null;
      }
    }
  }

  private List<BuildExecutionPlan> buildFallbackPlans(Path containerProjectPath) {
    List<BuildExecutionPlan> plans = new ArrayList<>();
    plans.add(
        new BuildExecutionPlan(
            RunnerProfile.GRADLE,
            containerProjectPath.toString(),
            List.of("./gradlew", "test"),
            "./gradlew",
            Map.of()));
    plans.add(
        new BuildExecutionPlan(
            RunnerProfile.AUTO,
            containerProjectPath.toString(),
            List.of("make", "test"),
            "make",
            Map.of()));
    plans.add(
        new BuildExecutionPlan(
            RunnerProfile.NPM,
            containerProjectPath.toString(),
            List.of("npm", "test"),
            "npm",
            RunnerProfile.NPM.defaultEnv()));
    return plans;
  }

  private DockerBuildRunResult executePlan(
      BuildExecutionPlan plan,
      MountConfiguration mountConfig,
      TempWorkspaceService.Workspace workspace,
      String requestId,
      String projectPath,
      Map<String, String> requestEnv,
      Duration timeout,
      String artifactId) {
    Map<String, String> env =
        mergeEnvs(
            requestEnv,
            plan.profile() == RunnerProfile.GRADLE ? mountConfig.gradleUserHome() : null,
            plan.envOverrides());
    List<String> dockerCommand =
        buildDockerCommand(mountConfig, plan.workDir(), plan.command(), env);
    if (log.isDebugEnabled()) {
      log.debug(
          "docker.build_runner invoking docker command: workspaceId={} projectPath={} profile={} command={}",
          workspace.workspaceId(),
          projectPath,
          plan.profile().id(),
          String.join(" ", dockerCommand));
    }
    Instant startedAt = Instant.now();
    Timer.Sample sample = Timer.start(meterRegistry);
    ProcessResult result;
    try {
      result = executeDockerCommand(dockerCommand, env, effectiveTimeout(timeout));
    } catch (RuntimeException ex) {
      runFailureCounter.increment();
      sample.stop(runTimer);
      log.warn(
          "docker_build_runner.failed requestId={} workspaceId={} profile={} message={}",
          requestId,
          workspace.workspaceId(),
          plan.profile().id(),
          ex.getMessage());
      throw ex;
    }
    Duration duration = Duration.between(startedAt, Instant.now());
    sample.stop(runTimer);
    runDurationSummary.record(duration.toMillis());

    boolean success = result.exitCode() == 0;
    if (success) {
      runSuccessCounter.increment();
    } else {
      runFailureCounter.increment();
    }

    ArtifactInfo artifactInfo =
        persistArtifacts(workspace.path(), artifactId, result.stdoutChunks(), result.stderrChunks());

    log.info(
        "docker_build_runner.completed requestId={} workspaceId={} profile={} exitCode={} status={} durationMs={}",
        requestId,
        workspace.workspaceId(),
        plan.profile().id(),
        result.exitCode(),
        success ? "success" : "failed",
        duration.toMillis());

    return new DockerBuildRunResult(
        workspace.workspaceId(),
        projectPath,
        plan.profile(),
        plan.runnerExecutable(),
        dockerCommand,
        result.exitCode(),
        success ? "success" : "failed",
        result.stdoutChunks(),
        result.stderrChunks(),
        duration,
        startedAt,
        Instant.now(),
        artifactInfo.artifactPath(),
        artifactInfo.files());
  }

  private ArtifactInfo persistArtifacts(
      Path workspacePath, String artifactId, List<String> stdout, List<String> stderr) {
    if (workspacePath == null) {
      return new ArtifactInfo(null, List.of());
    }
    try {
      Path artifactsRoot = workspacePath.resolve(".mcp-artifacts");
      Files.createDirectories(artifactsRoot);
      String sanitized = sanitizeArtifactId(artifactId);
      Path targetDir = artifactsRoot.resolve(sanitized);
      Files.createDirectories(targetDir);
      Files.writeString(targetDir.resolve("stdout.txt"), joinChunks(stdout));
      Files.writeString(targetDir.resolve("stderr.txt"), joinChunks(stderr));
      List<String> files;
      try (var stream = Files.list(targetDir)) {
        files = stream.map(path -> targetDir.relativize(path).toString()).sorted().toList();
      }
      return new ArtifactInfo(
          workspacePath.relativize(targetDir).toString().replace('\\', '/'), files);
    } catch (IOException ex) {
      log.warn("Failed to persist artifacts: {}", ex.getMessage());
      return new ArtifactInfo(null, List.of());
    }
  }

  private String joinChunks(List<String> chunks) {
    if (chunks == null || chunks.isEmpty()) {
      return "";
    }
    StringBuilder builder = new StringBuilder();
    for (String chunk : chunks) {
      builder.append(chunk);
    }
    return builder.toString();
  }

  private String sanitizeArtifactId(String artifactId) {
    String base =
        StringUtils.hasText(artifactId)
            ? artifactId.trim().replaceAll("[^a-zA-Z0-9-_]", "_")
            : "run-" + ARTIFACT_ID_FORMATTER.format(Instant.now());
    return base.isEmpty() ? "run-" + ARTIFACT_ID_FORMATTER.format(Instant.now()) : base;
  }

  private List<String> buildDockerCommand(
      MountConfiguration mountConfig,
      String workDir,
      List<String> command,
      Map<String, String> env) {
    List<String> dockerCommand = new ArrayList<>();
    dockerCommand.add(properties.getDockerBinary());
    dockerCommand.add("run");
    dockerCommand.add("--rm");

    appendVolumesFrom(dockerCommand);

    dockerCommand.addAll(mountConfig.dockerArgs());

    if (!properties.isEnableNetwork()) {
      dockerCommand.add("--network");
      dockerCommand.add("none");
    }

    if (properties.getCpuLimit() > 0) {
      dockerCommand.add("--cpus");
      dockerCommand.add(String.valueOf(properties.getCpuLimit()));
    }
    if (properties.getMemoryLimitGb() > 0) {
      dockerCommand.add("--memory");
      dockerCommand.add(String.format(Locale.ROOT, "%.1fg", properties.getMemoryLimitGb()));
    }

    env.forEach(
        (key, value) -> {
          if (StringUtils.hasText(key) && value != null) {
            dockerCommand.add("-e");
            dockerCommand.add(key + "=" + value);
          }
        });

    dockerCommand.add("-w");
    dockerCommand.add(workDir);

    properties.getDefaultArgs().forEach(dockerCommand::add);
    dockerCommand.add(properties.getImage());
    dockerCommand.addAll(command);
    return dockerCommand;
  }

  private MountConfiguration prepareMountConfiguration(TempWorkspaceService.Workspace workspace) {
    List<String> args = new ArrayList<>();
    String workspaceId = workspace.workspaceId();
    if (!StringUtils.hasText(workspaceId)) {
      throw new IllegalArgumentException("workspaceId must not be blank");
    }
    Path normalizedWorkspace = workspace.path().toAbsolutePath().normalize();
    Path containerWorkspaceRoot = normalizedWorkspace;
    String gradleUserHome = properties.getGradleCachePath();

    String workspaceVolume = properties.getWorkspaceVolume();
    if (StringUtils.hasText(workspaceVolume)) {
      String workspaceTargetBase = "/workspace";
      args.add("--mount");
      args.add("type=volume,source=" + workspaceVolume + ",target=" + workspaceTargetBase);
      containerWorkspaceRoot = Path.of(workspaceTargetBase, workspaceId);
    } else if (!properties.isVolumesFromSelf()) {
      String workspaceTargetBase = "/workspace";
      args.add("-v");
      args.add(normalizedWorkspace + ":" + workspaceTargetBase);
      containerWorkspaceRoot = Path.of(workspaceTargetBase);
    }

    String gradleCacheVolume = properties.getGradleCacheVolume();
    String gradleCachePath = properties.getGradleCachePath();
    if (StringUtils.hasText(gradleCacheVolume)) {
      String cacheTarget = "/gradle-cache";
      args.add("--mount");
      args.add("type=volume,source=" + gradleCacheVolume + ",target=" + cacheTarget);
      gradleUserHome = cacheTarget;
    } else if (!properties.isVolumesFromSelf()) {
      String cacheTarget = "/gradle-cache";
      if (StringUtils.hasText(gradleCachePath)) {
        args.add("-v");
        args.add(Path.of(gradleCachePath).toAbsolutePath().normalize() + ":" + cacheTarget);
        gradleUserHome = cacheTarget;
      } else {
        gradleUserHome = cacheTarget;
      }
    } else if (!StringUtils.hasText(gradleUserHome)) {
      gradleUserHome = "/gradle-cache";
    }

    if (gradleUserHome == null) {
      gradleUserHome = "/gradle-cache";
    }

    return new MountConfiguration(List.copyOf(args), containerWorkspaceRoot, gradleUserHome);
  }

  private List<String> sanitizeList(@Nullable List<String> values, List<String> defaults) {
    if (values == null || values.isEmpty()) {
      return new ArrayList<>(defaults);
    }
    return values.stream()
        .filter(Objects::nonNull)
        .map(String::trim)
        .filter(s -> !s.isEmpty())
        .collect(Collectors.toCollection(ArrayList::new));
  }

  private void appendVolumesFrom(List<String> dockerCommand) {
    if (properties.isVolumesFromSelf()) {
      String selfContainer = System.getenv("HOSTNAME");
      if (StringUtils.hasText(selfContainer)) {
        dockerCommand.add("--volumes-from");
        dockerCommand.add(selfContainer);
      } else {
        log.warn("HOSTNAME is not set; skipping --volumes-from for current container");
      }
    }
    List<String> additional = properties.getAdditionalVolumesFrom();
    if (additional != null) {
      for (String candidate : additional) {
        if (StringUtils.hasText(candidate)) {
          dockerCommand.add("--volumes-from");
          dockerCommand.add(candidate.trim());
        }
      }
    }
  }

  private Map<String, String> mergeEnvs(
      @Nullable Map<String, String> requestEnv,
      @Nullable String gradleUserHome,
      @Nullable Map<String, String> profileEnv) {
    Map<String, String> merged = new LinkedHashMap<>(properties.getDefaultEnv());
    if (profileEnv != null) {
      profileEnv.forEach(
          (key, value) -> {
            if (StringUtils.hasText(key) && value != null) {
              merged.put(key, value);
            }
          });
    }
    if (requestEnv != null) {
      requestEnv.forEach(
          (key, value) -> {
            if (StringUtils.hasText(key) && value != null) {
              merged.put(key, value);
            }
          });
    }
    if (StringUtils.hasText(gradleUserHome)) {
      merged.put("GRADLE_USER_HOME", gradleUserHome);
    }
    return merged;
  }

  private boolean ensureGradleWrapperExecutable(Path candidate) {
    if (!Files.isRegularFile(candidate)) {
      return false;
    }
    try {
      if (Files.isExecutable(candidate)) {
        return true;
      }
      try {
        Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(candidate);
        if (!permissions.contains(PosixFilePermission.OWNER_EXECUTE)) {
          permissions.add(PosixFilePermission.OWNER_EXECUTE);
          permissions.add(PosixFilePermission.GROUP_EXECUTE);
          permissions.add(PosixFilePermission.OTHERS_EXECUTE);
          Files.setPosixFilePermissions(candidate, permissions);
        }
      } catch (UnsupportedOperationException ex) {
        boolean updated = candidate.toFile().setExecutable(true, false);
        if (!updated) {
          log.warn("Failed to make gradlew executable at {}", candidate);
        }
      }
    } catch (IOException ex) {
      log.warn("Unable to adjust permissions for gradlew at {}: {}", candidate, ex.getMessage());
    }
    return Files.isExecutable(candidate);
  }

  private void validateWorkspacePath(Path workspacePath) {
    Path normalizedRoot = workspaceService.getWorkspaceRoot().toAbsolutePath().normalize();
    Path normalizedWorkspace = workspacePath.toAbsolutePath().normalize();
    if (!normalizedWorkspace.startsWith(normalizedRoot)) {
      throw new IllegalArgumentException(
          "Workspace path must reside under " + normalizedRoot + " but was " + normalizedWorkspace);
    }
  }

  private Path resolveProjectPath(Path workspaceRoot, @Nullable String projectPath) {
    if (!StringUtils.hasText(projectPath)) {
      return workspaceRoot;
    }
    Path candidate = workspaceRoot.resolve(projectPath).normalize();
    if (!candidate.startsWith(workspaceRoot)) {
      throw new IllegalArgumentException("projectPath must be within workspace");
    }
    if (!Files.exists(candidate)) {
      throw new IllegalArgumentException("projectPath does not exist: " + projectPath);
    }
    if (!Files.isDirectory(candidate)) {
      throw new IllegalArgumentException("projectPath must be a directory: " + projectPath);
    }
    return candidate;
  }

  private Duration effectiveTimeout(@Nullable Duration timeout) {
    if (timeout == null || timeout.isZero() || timeout.isNegative()) {
      return properties.getTimeout();
    }
    return timeout;
  }

  ProcessResult executeDockerCommand(List<String> command, Map<String, String> env, Duration timeout) {
    return runProcess(command, env, timeout);
  }

  private ProcessResult runProcess(List<String> command, Map<String, String> env, Duration timeout) {
    ProcessBuilder builder = new ProcessBuilder(command);
    builder.directory(properties.workspaceRootPath().toFile());
    builder.redirectErrorStream(false);
    Map<String, String> environment = builder.environment();
    environment.putAll(env);

    try {
      Process process = builder.start();
      LogCollector stdoutCollector = new LogCollector(properties.getMaxLogBytes());
      LogCollector stderrCollector = new LogCollector(properties.getMaxLogBytes());

      Thread stdoutReader =
          new Thread(() -> consumeStream(process.getInputStream(), stdoutCollector), "docker-runner-stdout");
      Thread stderrReader =
          new Thread(() -> consumeStream(process.getErrorStream(), stderrCollector), "docker-runner-stderr");
      stdoutReader.setDaemon(true);
      stderrReader.setDaemon(true);
      stdoutReader.start();
      stderrReader.start();

      boolean finished =
          process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
      if (!finished) {
        process.destroyForcibly();
        stdoutReader.join(TimeUnit.SECONDS.toMillis(2));
        stderrReader.join(TimeUnit.SECONDS.toMillis(2));
        throw new DockerRunnerException(
            "docker command timed out after " + timeout.toSeconds() + " seconds");
      }
      stdoutReader.join(TimeUnit.SECONDS.toMillis(2));
      stderrReader.join(TimeUnit.SECONDS.toMillis(2));
      return new ProcessResult(
          process.exitValue(), stdoutCollector.chunks(), stderrCollector.chunks());
    } catch (IOException ex) {
      throw new DockerRunnerException("Failed to start docker command", ex);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new DockerRunnerException("docker command interrupted", ex);
    }
  }

  private void consumeStream(InputStream stream, LogCollector collector) {
    try (BufferedReader reader =
        new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        collector.append(line + "\n");
      }
    } catch (IOException ex) {
      log.debug("Failed to read process stream: {}", ex.getMessage());
    }
  }

  public record DockerGradleRunInput(
      String workspaceId,
      String projectPath,
      List<String> tasks,
      List<String> arguments,
      Map<String, String> env,
      Duration timeout) {}

  public record DockerGradleRunResult(
      String workspaceId,
      String projectPath,
      String runnerExecutable,
      List<String> dockerCommand,
      int exitCode,
      String status,
      List<String> stdout,
      List<String> stderr,
      Duration duration,
      Instant startedAt,
      Instant finishedAt) {}

  public enum RunnerProfile {
    GRADLE("gradle", List.of("test"), Map.of()),
    MAVEN("maven", List.of("test"), Map.of()),
    NPM("npm", List.of("test"), Map.of("CI", "true")),
    PYTHON("python", List.of("pytest"), Map.of("PYTHONDONTWRITEBYTECODE", "1")),
    GO("go", List.of("test", "./..."), Map.of("GO111MODULE", "on")),
    AUTO("auto", List.of(), Map.of());

    private final String id;
    private final List<String> defaultTasks;
    private final Map<String, String> defaultEnv;

    RunnerProfile(String id, List<String> defaultTasks, Map<String, String> defaultEnv) {
      this.id = id;
      this.defaultTasks = List.copyOf(defaultTasks);
      this.defaultEnv = Map.copyOf(defaultEnv);
    }

    public String id() {
      return id;
    }

    public List<String> defaultTasks() {
      return defaultTasks;
    }

    public Map<String, String> defaultEnv() {
      return defaultEnv;
    }

    public static RunnerProfile fromString(String value) {
      if (!StringUtils.hasText(value)) {
        return RunnerProfile.AUTO;
      }
      String normalized = value.trim().toUpperCase(Locale.ROOT);
      for (RunnerProfile profile : values()) {
        if (profile.name().equalsIgnoreCase(normalized) || profile.id.equalsIgnoreCase(value)) {
          return profile;
        }
      }
      return RunnerProfile.AUTO;
    }
  }

  public record DockerBuildRunInput(
      String workspaceId,
      String projectPath,
      RunnerProfile profile,
      List<String> tasks,
      List<String> arguments,
      Map<String, String> env,
      Duration timeout,
      String artifactId,
      Boolean enableFallback) {}

  public record DockerBuildRunResult(
      String workspaceId,
      String projectPath,
      RunnerProfile profile,
      String runnerExecutable,
      List<String> dockerCommand,
      int exitCode,
      String status,
      List<String> stdout,
      List<String> stderr,
      Duration duration,
      Instant startedAt,
      Instant finishedAt,
      String artifactPath,
      List<String> artifacts) {}

  private record BuildExecutionPlan(
      RunnerProfile profile,
      String workDir,
      List<String> command,
      String runnerExecutable,
      Map<String, String> envOverrides) {}

  private record ArtifactInfo(String artifactPath, List<String> files) {}

  static final class ProcessResult {
    private final int exitCode;
    private final List<String> stdoutChunks;
    private final List<String> stderrChunks;

    ProcessResult(int exitCode, List<String> stdoutChunks, List<String> stderrChunks) {
      this.exitCode = exitCode;
      this.stdoutChunks = stdoutChunks;
      this.stderrChunks = stderrChunks;
    }

    int exitCode() {
      return exitCode;
    }

    List<String> stdoutChunks() {
      return stdoutChunks;
    }

    List<String> stderrChunks() {
      return stderrChunks;
    }
  }

  private static class LogCollector {
    private final long maxBytes;
    private final StringBuilder buffer = new StringBuilder();

    LogCollector(long maxBytes) {
      this.maxBytes = maxBytes;
    }

    void append(String data) {
      if (buffer.length() + data.length() > maxBytes) {
        int allowed = (int) Math.max(0, maxBytes - buffer.length());
        buffer.append(data, 0, Math.min(data.length(), allowed));
        return;
      }
      buffer.append(data);
    }

    List<String> chunks() {
      String content = buffer.toString();
      if (content.isEmpty()) {
        return List.of();
      }
      int chunkSize = 4000;
      int length = content.length();
      List<String> chunks = new ArrayList<>((length + chunkSize - 1) / chunkSize);
      for (int start = 0; start < length; start += chunkSize) {
        int end = Math.min(length, start + chunkSize);
        chunks.add(content.substring(start, end));
      }
      return chunks;
    }
  }

  public static class DockerRunnerException extends RuntimeException {
    public DockerRunnerException(String message) {
      super(message);
    }

    public DockerRunnerException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  private record MountConfiguration(List<String> dockerArgs, Path containerWorkspaceRoot, String gradleUserHome) {}

  private String normalizeRequestId(String requestId) {
    return requestId != null && !requestId.isBlank() ? requestId : "n/a";
  }
}
