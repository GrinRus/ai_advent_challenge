package com.aiadvent.mcp.backend.github.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aiadvent.mcp.backend.config.GitHubBackendProperties;
import com.aiadvent.mcp.backend.github.workspace.GitWorkspaceStateService.WorkspaceGitStateRequest;
import com.aiadvent.mcp.backend.github.workspace.GitWorkspaceStateService.WorkspaceGitStateResult;
import com.aiadvent.mcp.backend.github.workspace.TempWorkspaceService.CreateWorkspaceRequest;
import com.aiadvent.mcp.backend.github.workspace.TempWorkspaceService.WorkspaceGitMetadata;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitWorkspaceStateServiceTest {

  @TempDir Path tempDir;

  private TempWorkspaceService workspaceService;
  private GitWorkspaceStateService service;

  @BeforeEach
  void setUp() throws Exception {
    GitHubBackendProperties properties = new GitHubBackendProperties();
    properties.setWorkspaceRoot(tempDir.resolve("workspaces").toString());
    properties.setWorkspaceGitStateMaxEntries(50);
    properties.setWorkspaceGitStateMaxBytes(128 * 1024L);
    properties.setWorkspaceGitStateTimeout(Duration.ofSeconds(10));
    workspaceService = new TempWorkspaceService(properties, new SimpleMeterRegistry());
    workspaceService.afterPropertiesSet();
    service = new GitWorkspaceStateService(workspaceService, properties, new SimpleMeterRegistry());
  }

  @Test
  void returnsCleanStatusForPristineRepo() throws Exception {
    var workspace = initWorkspaceWithGit();
    WorkspaceGitStateResult result =
        service.inspect(new WorkspaceGitStateRequest(workspace.workspaceId(), true, true, null, false));
    assertThat(result.status().clean()).isTrue();
    assertThat(result.branch().name()).isEqualTo("main");
    assertThat(result.branch().headSha()).isNotBlank();
    assertThat(result.files()).isEmpty();
  }

  @Test
  void reportsUntrackedAndModifiedFiles() throws Exception {
    var workspace = initWorkspaceWithGit();
    Path readme = workspace.path().resolve("README.md");
    Files.writeString(readme, "changed\n", StandardCharsets.UTF_8);
    Path newFile = workspace.path().resolve("notes.txt");
    Files.writeString(newFile, "draft", StandardCharsets.UTF_8);

    WorkspaceGitStateResult result =
        service.inspect(new WorkspaceGitStateRequest(workspace.workspaceId(), true, true, null, false));

    assertThat(result.status().clean()).isFalse();
    assertThat(result.status().unstaged()).isGreaterThan(0);
    assertThat(result.status().untracked()).isGreaterThan(0);
    assertThat(result.files()).isNotEmpty();
    assertThat(result.files().stream().anyMatch(entry -> entry.path().equals("notes.txt"))).isTrue();
  }

  @Test
  void failsWhenGitMetadataMissing() {
    var workspace =
        workspaceService.createWorkspace(
            new CreateWorkspaceRequest("demo/repo", "heads/main", UUID.randomUUID().toString()));
    assertThatThrownBy(
            () ->
                service.inspect(
                    new WorkspaceGitStateRequest(workspace.workspaceId(), true, true, null, false)))
        .isInstanceOf(GitWorkspaceStateService.WorkspaceGitStateException.class);
  }

  private TempWorkspaceService.Workspace initWorkspaceWithGit() throws Exception {
    var workspace =
        workspaceService.createWorkspace(
            new CreateWorkspaceRequest("demo/repo", "heads/main", UUID.randomUUID().toString()));
    Path root = workspace.path();
    runGit(root, "init");
    runGit(root, "config", "user.name", "Test");
    runGit(root, "config", "user.email", "test@example.com");
    Files.writeString(root.resolve("README.md"), "hello\n", StandardCharsets.UTF_8);
    runGit(root, "add", "README.md");
    runGit(root, "commit", "-m", "init");
    String headSha = runGit(root, "rev-parse", "HEAD").trim();
    workspaceService.updateGitMetadata(
        workspace.workspaceId(),
        new WorkspaceGitMetadata("main", headSha, "refs/heads/main", "origin/main"));
    return workspace;
  }

  private String runGit(Path directory, String... command) throws Exception {
    ProcessBuilder builder = new ProcessBuilder();
    builder.command(concat("git", command));
    builder.directory(directory.toFile());
    builder.environment().put("GIT_TERMINAL_PROMPT", "0");
    builder.redirectErrorStream(true);
    Process process = builder.start();
    byte[] output = process.getInputStream().readAllBytes();
    if (!process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) {
      process.destroyForcibly();
      throw new IllegalStateException("git command timed out");
    }
    if (process.exitValue() != 0) {
      throw new IllegalStateException("git command failed: " + new String(output, StandardCharsets.UTF_8));
    }
    return new String(output, StandardCharsets.UTF_8);
  }

  private List<String> concat(String first, String... rest) {
    List<String> commands = new ArrayList<>();
    commands.add(first);
    commands.addAll(java.util.Arrays.asList(rest));
    return commands;
  }
}
