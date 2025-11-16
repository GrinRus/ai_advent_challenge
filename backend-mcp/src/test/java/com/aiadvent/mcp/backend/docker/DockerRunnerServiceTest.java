package com.aiadvent.mcp.backend.docker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.aiadvent.mcp.backend.config.DockerRunnerProperties;
import com.aiadvent.mcp.backend.docker.DockerRunnerService.DockerBuildRunInput;
import com.aiadvent.mcp.backend.docker.DockerRunnerService.DockerBuildRunResult;
import com.aiadvent.mcp.backend.docker.DockerRunnerService.RunnerProfile;
import com.aiadvent.mcp.backend.github.workspace.TempWorkspaceService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

class DockerRunnerServiceTest {

  @TempDir Path tempDir;

  private TestDockerRunnerService service;
  private TempWorkspaceService workspaceService;
  private Path workspacePath;

  @BeforeEach
  void setUp() throws IOException {
    DockerRunnerProperties properties = new DockerRunnerProperties();
    properties.setWorkspaceRoot(tempDir.resolve("workspaces").toString());
    properties.setImage("mcr.microsoft.com/devcontainers/javascript-node:0-20");
    properties.setDefaultArgs(List.of());

    workspaceService = Mockito.mock(TempWorkspaceService.class);
    when(workspaceService.getWorkspaceRoot()).thenReturn(tempDir.resolve("workspaces"));
    service = new TestDockerRunnerService(properties, workspaceService);

    workspacePath = tempDir.resolve("workspaces/ws-test");
    Files.createDirectories(workspacePath);
    Path project = workspacePath.resolve("node-app");
    Files.createDirectories(project);
    Files.writeString(project.resolve("package.json"), "{\"name\":\"demo\"}");
    Files.writeString(project.resolve("package-lock.json"), "{}");

    TempWorkspaceService.Workspace workspace =
        new TempWorkspaceService.Workspace(
            "ws-test",
            workspacePath,
            java.time.Instant.now(),
            java.time.Instant.now().plus(Duration.ofHours(1)),
            "req-1",
            "owner/repo",
            "main",
            0L,
            null,
            List.of(),
            null,
            null,
            null);

    when(workspaceService.findWorkspace("ws-test")).thenReturn(Optional.of(workspace));
  }

  @Test
  void detectsNpmProfileAndCreatesArtifacts() {
    DockerBuildRunInput input =
        new DockerBuildRunInput(
            "ws-test",
            "node-app",
            RunnerProfile.AUTO,
            List.of(),
            List.of(),
            Map.of(),
            Duration.ofSeconds(1),
            "analysis-123",
            false);
    service.enqueueProcessResult(0, "ok", "");
    DockerBuildRunResult result = service.runBuild(input);

    assertThat(result.profile()).isEqualTo(RunnerProfile.NPM);
    assertThat(result.artifactPath()).isNotBlank();
    Path artifactDir = workspacePath.resolve(result.artifactPath());
    assertThat(Files.exists(artifactDir.resolve("stdout.txt"))).isTrue();
    assertThat(Files.exists(artifactDir.resolve("stderr.txt"))).isTrue();
  }

  @Test
  void fallbackPlanExecutesWhenDetectionFails() throws IOException {
    // workspace without known build files
    Path unknownProject = workspacePath.resolve("unknown");
    Files.createDirectories(unknownProject);
    DockerBuildRunInput input =
        new DockerBuildRunInput(
            "ws-test",
            "unknown",
            RunnerProfile.AUTO,
            List.of(),
            List.of(),
            Map.of(),
            Duration.ofSeconds(1),
            "analysis-456",
            true);

    service.enqueueProcessResult(1, "fail", "error");
    service.enqueueProcessResult(0, "success", "");

    DockerBuildRunResult result = service.runBuild(input);

    assertThat(result.exitCode()).isZero();
    assertThat(result.profile()).isEqualTo(RunnerProfile.AUTO);
    assertThat(service.executedCommands().size()).isEqualTo(2);
  }

  private static class TestDockerRunnerService extends DockerRunnerService {
    private final Queue<ProcessResult> queue = new ArrayDeque<>();
    private final List<List<String>> executed = new ArrayList<>();

    TestDockerRunnerService(
        DockerRunnerProperties properties, TempWorkspaceService workspaceService) {
      super(properties, workspaceService, new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    }

    void enqueueProcessResult(int exitCode, String stdout, String stderr) {
      queue.add(new ProcessResult(exitCode, List.of(stdout), List.of(stderr)));
    }

    List<List<String>> executedCommands() {
      return executed;
    }

    @Override
    ProcessResult executeDockerCommand(
        List<String> command, Map<String, String> env, Duration timeout) {
      executed.add(List.copyOf(command));
      if (queue.isEmpty()) {
        throw new IllegalStateException("No stubbed process result available");
      }
      return queue.remove();
    }
  }
}
