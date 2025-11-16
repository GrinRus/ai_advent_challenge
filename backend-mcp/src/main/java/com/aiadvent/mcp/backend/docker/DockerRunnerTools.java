package com.aiadvent.mcp.backend.docker;

import com.aiadvent.mcp.backend.docker.DockerRunnerService.DockerBuildRunInput;
import com.aiadvent.mcp.backend.docker.DockerRunnerService.DockerBuildRunResult;
import com.aiadvent.mcp.backend.docker.DockerRunnerService.RunnerProfile;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@Profile("docker")
public class DockerRunnerTools {

  private final DockerRunnerService dockerRunnerService;

  public DockerRunnerTools(DockerRunnerService dockerRunnerService) {
    this.dockerRunnerService = Objects.requireNonNull(dockerRunnerService, "dockerRunnerService");
  }

  @Tool(
      name = "docker.build_runner",
      description =
          "Запускает сборку/тесты внутри многоязычного Docker-образа. Требует workspaceId."
              + " Определяет профиль автоматически (Gradle/Maven/npm/Python/Go) по файлам проекта или принимает profile="
              + "gradle|maven|npm|python|go; tasks/arguments передаются runner'у как есть."
              + " Поддерживает artifacts (analysisId) и fallback-последовательность для неизвестных проектов.")
  DockerBuildRunnerResponse runBuild(
      @JsonProperty("workspaceId") String workspaceId,
      @JsonProperty("projectPath") String projectPath,
      @JsonProperty("profile") String profile,
      @JsonProperty("tasks") List<String> tasks,
      @JsonProperty("arguments") List<String> arguments,
      @JsonProperty("env") Map<String, String> env,
      @JsonProperty("timeoutSeconds") Integer timeoutSeconds,
      @JsonProperty("analysisId") String analysisId,
      @JsonProperty("enableFallback") Boolean enableFallback) {
    if (!StringUtils.hasText(workspaceId)) {
      throw new IllegalArgumentException("workspaceId must not be blank");
    }
    RunnerProfile runnerProfile = RunnerProfile.fromString(profile);
    boolean fallbackEnabled =
        enableFallback != null ? enableFallback : runnerProfile == RunnerProfile.AUTO;
    DockerBuildRunResult result =
        dockerRunnerService.runBuild(
            new DockerBuildRunInput(
                workspaceId,
                projectPath,
                runnerProfile,
                tasks,
                arguments,
                env,
                toDuration(timeoutSeconds),
                analysisId,
                fallbackEnabled));

    return new DockerBuildRunnerResponse(
        result.workspaceId(),
        result.projectPath(),
        result.profile().id(),
        result.runnerExecutable(),
        result.dockerCommand(),
        result.exitCode(),
        result.status(),
        result.stdout(),
        result.stderr(),
        result.duration().toMillis(),
        result.startedAt(),
        result.finishedAt(),
        result.artifactPath(),
        result.artifacts());
  }

  private Duration toDuration(Integer timeoutSeconds) {
    if (timeoutSeconds == null || timeoutSeconds <= 0) {
      return null;
    }
    return Duration.ofSeconds(timeoutSeconds.longValue());
  }

  public record DockerBuildRunnerResponse(
      String workspaceId,
      String projectPath,
      String profile,
      String runnerExecutable,
      List<String> dockerCommand,
      int exitCode,
      String status,
      List<String> stdout,
      List<String> stderr,
      long durationMs,
      Instant startedAt,
      Instant finishedAt,
      String artifactPath,
      List<String> artifacts) {}
}
