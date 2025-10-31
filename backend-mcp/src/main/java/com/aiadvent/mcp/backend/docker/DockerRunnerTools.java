package com.aiadvent.mcp.backend.docker;

import com.aiadvent.mcp.backend.docker.DockerRunnerService.DockerGradleRunInput;
import com.aiadvent.mcp.backend.docker.DockerRunnerService.DockerGradleRunResult;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class DockerRunnerTools {

  private final DockerRunnerService dockerRunnerService;

  public DockerRunnerTools(DockerRunnerService dockerRunnerService) {
    this.dockerRunnerService = Objects.requireNonNull(dockerRunnerService, "dockerRunnerService");
  }

  @Tool(
      name = "docker.gradle_runner",
      description =
          "Запускает Gradle-задачи внутри Docker-образа. Требует workspaceId (из github_repository_fetch). "
              + "Параметр projectPath указывает относительный путь к проекту в workspace (необязательно). "
              + "tasks — список целей Gradle, arguments — дополнительные аргументы (например, --info). "
              + "env задаёт переменные окружения (карта имя → значение). timeoutSeconds ограничивает время выполнения. "
              + "Ответ содержит exitCode, статус success/failed, dockerCommand, stdout/stderr (в чанках) и длительность.")
  DockerGradleRunnerResponse runGradle(DockerGradleRunnerRequest request) {
    if (request == null || !StringUtils.hasText(request.workspaceId())) {
      throw new IllegalArgumentException("workspaceId must not be blank");
    }
    DockerGradleRunResult result =
        dockerRunnerService.runGradle(
            new DockerGradleRunInput(
                request.workspaceId(),
                request.projectPath(),
                request.tasks(),
                request.arguments(),
                request.env(),
                toDuration(request.timeoutSeconds())));

    return new DockerGradleRunnerResponse(
        result.workspaceId(),
        result.projectPath(),
        result.runnerExecutable(),
        result.dockerCommand(),
        result.exitCode(),
        result.status(),
        result.stdout(),
        result.stderr(),
        result.duration().toMillis(),
        result.startedAt(),
        result.finishedAt());
  }

  private Duration toDuration(Integer timeoutSeconds) {
    if (timeoutSeconds == null || timeoutSeconds <= 0) {
      return null;
    }
    return Duration.ofSeconds(timeoutSeconds.longValue());
  }

  public record DockerGradleRunnerRequest(
      @JsonProperty("workspaceId") String workspaceId,
      @JsonProperty("projectPath") String projectPath,
      @JsonProperty("tasks") List<String> tasks,
      @JsonProperty("arguments") List<String> arguments,
      @JsonProperty("env") Map<String, String> env,
      @JsonProperty("timeoutSeconds") Integer timeoutSeconds) {}

  public record DockerGradleRunnerResponse(
      String workspaceId,
      String projectPath,
      String runnerExecutable,
      List<String> dockerCommand,
      int exitCode,
      String status,
      List<String> stdout,
      List<String> stderr,
      long durationMs,
      Instant startedAt,
      Instant finishedAt) {}
}
