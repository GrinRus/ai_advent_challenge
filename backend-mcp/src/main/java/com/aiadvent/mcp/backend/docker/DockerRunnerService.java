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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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

  private final DockerRunnerProperties properties;
  private final TempWorkspaceService workspaceService;
  private final MeterRegistry meterRegistry;
  private final Timer runTimer;
  private final Counter runSuccessCounter;
  private final Counter runFailureCounter;
  private final DistributionSummary runDurationSummary;

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
  }

  public DockerGradleRunResult runGradle(DockerGradleRunInput input) {
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
    List<String> tasks = sanitizeList(input.tasks(), List.of("test"));
    List<String> gradleArgs = sanitizeList(input.arguments(), List.of());

    boolean hasProjectWrapper = Files.isRegularFile(projectAbsolute.resolve("gradlew"));
    boolean hasRootWrapper = Files.isRegularFile(workspacePath.resolve("gradlew"));

    MountConfiguration mountConfig = prepareMountConfiguration(workspace);
    Path containerWorkspaceRoot = mountConfig.containerWorkspaceRoot();
    Path containerProjectPath =
        StringUtils.hasText(projectPath)
            ? containerWorkspaceRoot.resolve(projectPath)
            : containerWorkspaceRoot;
    String workDir = containerProjectPath.toString();
    List<String> command = new ArrayList<>();
    String runnerExecutable;
    if (hasProjectWrapper) {
      runnerExecutable = "./gradlew";
      command.add(runnerExecutable);
      if (!gradleArgs.isEmpty()) {
        command.addAll(gradleArgs);
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
      if (!gradleArgs.isEmpty()) {
        command.addAll(gradleArgs);
      }
      command.addAll(tasks);
    } else {
      runnerExecutable = "gradle";
      command.add(runnerExecutable);
      if (StringUtils.hasText(projectPath)) {
        command.add("-p");
        command.add(projectPath);
      }
      if (!gradleArgs.isEmpty()) {
        command.addAll(gradleArgs);
      }
      command.addAll(tasks);
    }

    List<String> dockerCommand = buildDockerCommand(mountConfig, workDir, command, input);
    if (log.isDebugEnabled()) {
      log.debug(
          "docker.gradle_runner invoking docker command: workspaceId={}, projectPath={}, command={}",
          workspaceId,
          projectPath,
          String.join(" ", dockerCommand));
    }

    Map<String, String> env = mergeEnvs(input.env(), mountConfig.gradleUserHome());
    Instant startedAt = Instant.now();
    Timer.Sample sample = Timer.start(meterRegistry);
    ProcessResult result;
    try {
      result = runProcess(dockerCommand, env, effectiveTimeout(input.timeout()));
    } catch (RuntimeException ex) {
      runFailureCounter.increment();
      sample.stop(runTimer);
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

    return new DockerGradleRunResult(
        workspaceId,
        projectPath,
        runnerExecutable,
        dockerCommand,
        result.exitCode(),
        success ? "success" : "failed",
        result.stdoutChunks(),
        result.stderrChunks(),
        duration,
        startedAt,
        Instant.now());
  }

  private List<String> buildDockerCommand(
      MountConfiguration mountConfig, String workDir, List<String> gradleCommand, DockerGradleRunInput input) {
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

    for (Map.Entry<String, String> entry : properties.getDefaultEnv().entrySet()) {
      dockerCommand.add("-e");
      dockerCommand.add(entry.getKey() + "=" + entry.getValue());
    }

    Map<String, String> requestEnv = input.env();
    if (requestEnv != null) {
      for (Map.Entry<String, String> entry : requestEnv.entrySet()) {
        if (StringUtils.hasText(entry.getKey()) && entry.getValue() != null) {
          dockerCommand.add("-e");
          dockerCommand.add(entry.getKey() + "=" + entry.getValue());
        }
      }
    }

    dockerCommand.add("-w");
    dockerCommand.add(workDir);

    properties.getDefaultArgs().forEach(dockerCommand::add);
    dockerCommand.add(properties.getImage());
    dockerCommand.addAll(gradleCommand);
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

  private Map<String, String> mergeEnvs(@Nullable Map<String, String> requestEnv, String gradleUserHome) {
    Map<String, String> merged = new LinkedHashMap<>(properties.getDefaultEnv());
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

  private record ProcessResult(int exitCode, List<String> stdoutChunks, List<String> stderrChunks) {}

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
}
