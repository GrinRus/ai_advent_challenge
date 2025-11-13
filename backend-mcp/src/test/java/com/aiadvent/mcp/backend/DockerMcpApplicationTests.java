package com.aiadvent.mcp.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aiadvent.mcp.backend.docker.DockerRunnerService;
import com.aiadvent.mcp.backend.docker.DockerRunnerService.DockerGradleRunInput;
import com.aiadvent.mcp.backend.docker.DockerRunnerService.DockerGradleRunResult;
import com.aiadvent.mcp.backend.github.workspace.TempWorkspaceService;
import com.aiadvent.mcp.backend.github.workspace.TempWorkspaceService.CreateWorkspaceRequest;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(classes = McpApplication.class, properties = "spring.profiles.active=docker")
class DockerMcpApplicationTests {

  private static final Path WORKSPACE_ROOT = createTempDir("gradle-workspaces");
  private static final Path GRADLE_CACHE_ROOT = createTempDir("gradle-cache");
  private static final String FAKE_DOCKER_BINARY = createFakeDockerBinary();

  @Autowired private DockerRunnerService dockerRunnerService;
  @Autowired private TempWorkspaceService workspaceService;

  @DynamicPropertySource
  static void registerProperties(DynamicPropertyRegistry registry) {
    registry.add("github.backend.workspace-root", () -> WORKSPACE_ROOT.toString());
    registry.add("docker.runner.workspace-root", () -> WORKSPACE_ROOT.toString());
    registry.add("docker.runner.gradle-cache-path", () -> GRADLE_CACHE_ROOT.toString());
    registry.add("docker.runner.docker-binary", () -> FAKE_DOCKER_BINARY);
    registry.add("docker.runner.volumes-from-self", () -> "false");
    registry.add("docker.runner.workspace-volume", () -> "");
    registry.add("docker.runner.gradle-cache-volume", () -> "");
    registry.add("spring.liquibase.enabled", () -> "false");
  }

  @Test
  void dockerGradleRunnerCollectsStdoutAndSucceeds() throws Exception {
    TempWorkspaceService.Workspace workspace = createWorkspace("request-success");
    createGradleWrapper(workspace.path());

    DockerGradleRunResult result =
        dockerRunnerService.runGradle(
            new DockerGradleRunInput(
                workspace.workspaceId(),
                null,
                List.of("test"),
                List.of("--info"),
                Map.of("CUSTOM_ENV", "demo"),
                Duration.ofSeconds(15)));

    assertThat(result.status()).isEqualTo("success");
    assertThat(result.exitCode()).isZero();
    assertThat(result.dockerCommand().get(0)).isEqualTo(FAKE_DOCKER_BINARY);
    assertThat(result.stdout()).anySatisfy(chunk -> assertThat(chunk).contains("Simulated Gradle run"));
    assertThat(result.stderr()).anySatisfy(chunk -> assertThat(chunk).contains("diagnostics"));
  }

  @Test
  void dockerGradleRunnerPropagatesExitCodeAndLogsFailures() throws Exception {
    TempWorkspaceService.Workspace workspace = createWorkspace("request-fail");
    createGradleWrapper(workspace.path());

    DockerGradleRunResult result =
        dockerRunnerService.runGradle(
            new DockerGradleRunInput(
                workspace.workspaceId(),
                null,
                List.of("test"),
                List.of(),
                Map.of("FAKE_DOCKER_BEHAVIOR", "fail"),
                Duration.ofSeconds(15)));

    assertThat(result.status()).isEqualTo("failed");
    assertThat(result.exitCode()).isEqualTo(17);
    assertThat(result.stderr()).anySatisfy(chunk -> assertThat(chunk).contains("Simulated failure"));
  }

  @Test
  void dockerGradleRunnerTimesOut() throws Exception {
    TempWorkspaceService.Workspace workspace = createWorkspace("request-timeout");
    createGradleWrapper(workspace.path());

    assertThatThrownBy(
            () ->
                dockerRunnerService.runGradle(
                    new DockerGradleRunInput(
                        workspace.workspaceId(),
                        null,
                        List.of("test"),
                        List.of(),
                        Map.of("FAKE_DOCKER_BEHAVIOR", "timeout"),
                        Duration.ofSeconds(1))))
        .isInstanceOf(DockerRunnerService.DockerRunnerException.class)
        .hasMessageContaining("timed out");
  }

  private TempWorkspaceService.Workspace createWorkspace(String requestId) {
    return workspaceService.createWorkspace(
        new CreateWorkspaceRequest("example/demo-service", "refs/heads/main", requestId));
  }

  private void createGradleWrapper(Path workspacePath) throws IOException {
    Path gradlew = workspacePath.resolve("gradlew");
    Files.writeString(
        gradlew,
        "#!/bin/bash\n" + "echo \"gradlew invoked\"\n" + "exit 0\n",
        StandardCharsets.UTF_8);
    try {
      Files.setPosixFilePermissions(
          gradlew,
          Set.of(
              PosixFilePermission.OWNER_EXECUTE,
              PosixFilePermission.OWNER_READ,
              PosixFilePermission.GROUP_EXECUTE,
              PosixFilePermission.GROUP_READ,
              PosixFilePermission.OTHERS_EXECUTE,
              PosixFilePermission.OTHERS_READ));
    } catch (UnsupportedOperationException ignored) {
      gradlew.toFile().setExecutable(true, false);
    }
  }

  private static Path createTempDir(String prefix) {
    try {
      Path dir = Files.createTempDirectory(prefix);
      dir.toFile().deleteOnExit();
      return dir;
    } catch (IOException ex) {
      throw new UncheckedIOException("Failed to create temp directory", ex);
    }
  }

  private static String createFakeDockerBinary() {
    try {
      Path scriptDir = createTempDir("fake-docker");
      Path script = scriptDir.resolve("fake-docker.sh");
      String content =
          """
          #!/bin/bash
          if [[ "$FAKE_DOCKER_BEHAVIOR" == "fail" ]]; then
            echo "Simulated failure for $*" 1>&2
            exit 17
          fi
          if [[ "$FAKE_DOCKER_BEHAVIOR" == "timeout" ]]; then
            sleep 5
          fi
          echo "Simulated Gradle run for $*"
          echo "diagnostics: container executed" 1>&2
          exit 0
          """;
      Files.writeString(script, content, StandardCharsets.UTF_8);
      script.toFile().setExecutable(true, false);
      script.toFile().deleteOnExit();
      return script.toAbsolutePath().toString();
    } catch (IOException ex) {
      throw new UncheckedIOException("Failed to create fake docker binary", ex);
    }
  }
}
