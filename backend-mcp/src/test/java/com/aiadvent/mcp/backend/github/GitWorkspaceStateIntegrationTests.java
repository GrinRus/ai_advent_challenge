package com.aiadvent.mcp.backend.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aiadvent.mcp.backend.config.GitHubBackendProperties;
import com.aiadvent.mcp.backend.github.GitHubRepositoryService.CheckoutStrategy;
import com.aiadvent.mcp.backend.github.GitHubRepositoryService.CommitAuthor;
import com.aiadvent.mcp.backend.github.GitHubRepositoryService.CommitWorkspaceDiffInput;
import com.aiadvent.mcp.backend.github.GitHubRepositoryService.CommitWorkspaceDiffResult;
import com.aiadvent.mcp.backend.github.GitHubRepositoryService.CreateBranchInput;
import com.aiadvent.mcp.backend.github.GitHubRepositoryService.CreateBranchResult;
import com.aiadvent.mcp.backend.github.GitHubRepositoryService.FetchRepositoryInput;
import com.aiadvent.mcp.backend.github.GitHubRepositoryService.FetchRepositoryResult;
import com.aiadvent.mcp.backend.github.GitHubRepositoryService.GitFetchOptions;
import com.aiadvent.mcp.backend.github.GitHubRepositoryService.PushBranchInput;
import com.aiadvent.mcp.backend.github.GitHubRepositoryService.PushBranchResult;
import com.aiadvent.mcp.backend.github.GitHubRepositoryService.RepositoryRef;
import com.aiadvent.mcp.backend.github.rag.RepoRagIndexScheduler;
import com.aiadvent.mcp.backend.github.workspace.GitWorkspaceStateService;
import com.aiadvent.mcp.backend.github.workspace.GitWorkspaceStateService.WorkspaceGitStateRequest;
import com.aiadvent.mcp.backend.github.workspace.GitWorkspaceStateService.WorkspaceGitStateResult;
import com.aiadvent.mcp.backend.github.workspace.TempWorkspaceService;
import com.aiadvent.mcp.backend.github.workspace.TempWorkspaceService.Workspace;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kohsuke.github.GHFileNotFoundException;
import org.kohsuke.github.GHRef;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;

final class GitWorkspaceStateIntegrationTests {

  private static final String OWNER = "acme";
  private static final String REPOSITORY = "demo";

  @TempDir Path tempDir;

  private Path remoteGitDir;
  private GitHubBackendProperties properties;
  private TempWorkspaceService workspaceService;
  private GitWorkspaceStateService gitStateService;
  private GitHubRepositoryService repositoryService;
  private GitHubClientExecutor executor;
  private RepoRagIndexScheduler indexScheduler;
  private GitHubTokenManager tokenManager;
  private GitHub githubMock;
  private GHRepository repoMock;

  @BeforeEach
  void setUp() throws Exception {
    remoteGitDir = tempDir.resolve("remote.git");
    initializeRemoteRepository();

    properties = new GitHubBackendProperties();
    properties.setWorkspaceRoot(tempDir.resolve("workspaces").toString());
    properties.setCloneBaseUrl(remoteGitDir.toUri().toString());
    properties.setWorkspaceGitStateMaxEntries(200);
    properties.setWorkspaceGitStateMaxBytes(64 * 1024L);

    workspaceService = new TempWorkspaceService(properties, null);
    workspaceService.afterPropertiesSet();

    gitStateService = new GitWorkspaceStateService(workspaceService, properties, null);

    githubMock = mock(GitHub.class);
    repoMock = mock(GHRepository.class);
    when(githubMock.getRepository(OWNER + "/" + REPOSITORY)).thenReturn(repoMock);
    when(repoMock.hasPushAccess()).thenReturn(true);
    when(repoMock.getRef(any())).thenAnswer(invocation -> toGhRef((String) invocation.getArgument(0)));
    when(repoMock.createRef(any(), any()))
        .thenAnswer(
            invocation -> {
              String ref = invocation.getArgument(0);
              String sha = invocation.getArgument(1);
              updateRemoteRef(ref, sha);
              return toGhRef(ref.replace("refs/", ""));
            });

    executor = mock(GitHubClientExecutor.class);
    when(executor.execute(any()))
        .thenAnswer(
            invocation -> {
              Function<GitHub, ?> function = invocation.getArgument(0);
              return function.apply(githubMock);
            });
    doAnswer(
            invocation -> {
              Consumer<GitHub> consumer = invocation.getArgument(0);
              consumer.accept(githubMock);
              return null;
            })
        .when(executor)
        .executeVoid(any());

    indexScheduler = mock(RepoRagIndexScheduler.class);
    tokenManager = mock(GitHubTokenManager.class);
    when(tokenManager.currentToken()).thenReturn("");

    repositoryService =
        new GitHubRepositoryService(executor, properties, workspaceService, indexScheduler, tokenManager, null);
  }

  @AfterEach
  void tearDown() throws Exception {
    workspaceService.destroy();
  }

  @Test
  void fetchRepositoryProvidesCleanGitState() throws Exception {
    RepositoryRef repoRef = new RepositoryRef(OWNER, REPOSITORY, "heads/main");
    FetchRepositoryResult fetchResult =
        repositoryService.fetchRepository(new FetchRepositoryInput(repoRef, cloneOptions(), "req-clean"));

    WorkspaceGitStateResult state =
        gitStateService.inspect(new WorkspaceGitStateRequest(fetchResult.workspaceId(), true, true, null, false));

    assertThat(state.branch().name()).isEqualTo("main");
    assertThat(state.status().clean()).isTrue();
    assertThat(state.files()).isEmpty();
    assertThat(state.truncated()).isFalse();
  }

  @Test
  void workspaceGitStateDetectsDirtyFilesAndClearsAfterCommit() throws Exception {
    RepositoryRef repoRef = new RepositoryRef(OWNER, REPOSITORY, "heads/main");
    FetchRepositoryResult fetchResult =
        repositoryService.fetchRepository(new FetchRepositoryInput(repoRef, cloneOptions(), "req-dirty"));

    String workspaceId = fetchResult.workspaceId();
    Workspace workspace = workspaceService.findWorkspace(workspaceId).orElseThrow();
    Path workspacePath = workspace.path();

    CreateBranchResult branchResult =
        repositoryService.createBranch(new CreateBranchInput(repoRef, workspaceId, "feature/git-state", null));
    assertThat(branchResult.branchName()).isEqualTo("feature/git-state");

    Files.writeString(workspacePath.resolve("README.md"), "dirty change\n", StandardOpenOption.APPEND);
    Files.writeString(workspacePath.resolve("notes.txt"), "draft\n", StandardCharsets.UTF_8);
    Files.createDirectories(workspacePath.resolve("docs"));
    Files.writeString(workspacePath.resolve("docs/log.md"), "tmp\n", StandardCharsets.UTF_8);

    WorkspaceGitStateResult dirtyState =
        gitStateService.inspect(new WorkspaceGitStateRequest(workspaceId, true, true, 1, false));

    assertThat(dirtyState.status().clean()).isFalse();
    assertThat(dirtyState.files()).hasSize(1);
    assertThat(dirtyState.truncated()).isTrue();

    CommitWorkspaceDiffResult commitResult =
        repositoryService.commitWorkspaceDiff(
            new CommitWorkspaceDiffInput(
                workspaceId,
                "feature/git-state",
                new CommitAuthor("CI Bot", "ci@example.com"),
                "feat: sync state"));

    PushBranchResult pushResult =
        repositoryService.pushBranch(new PushBranchInput(repoRef, workspaceId, "feature/git-state", false));
    assertThat(pushResult.localHeadSha()).isEqualTo(commitResult.commitSha());

    WorkspaceGitStateResult cleanState =
        gitStateService.inspect(new WorkspaceGitStateRequest(workspaceId, false, false, null, true));

    assertThat(cleanState.status().clean()).isTrue();
    assertThat(cleanState.branch().name()).isEqualTo("feature/git-state");
    assertThat(cleanState.branch().headSha()).isEqualTo(pushResult.remoteHeadSha());
    assertThat(cleanState.branch().upstream()).isEqualTo("origin/feature/git-state");
    assertThat(cleanState.files()).isEmpty();
    assertThat(cleanState.truncated()).isFalse();
  }

  private GitFetchOptions cloneOptions() {
    return new GitFetchOptions(
        CheckoutStrategy.CLONE_WITH_SUBMODULES,
        true,
        false,
        Duration.ofSeconds(60),
        Duration.ofSeconds(60),
        null,
        false);
  }

  private void initializeRemoteRepository() throws Exception {
    runGit(tempDir, "init", "--bare", remoteGitDir.getFileName().toString());

    Path seed = tempDir.resolve("seed");
    Files.createDirectories(seed);
    runGit(seed, "init");
    runGit(seed, "config", "user.name", "Test Bot");
    runGit(seed, "config", "user.email", "test@example.com");
    Files.writeString(seed.resolve("README.md"), "hello\n", StandardCharsets.UTF_8);
    runGit(seed, "add", "README.md");
    runGit(seed, "commit", "-m", "init");
    runGit(seed, "branch", "-M", "main");
    runGit(seed, "remote", "add", "origin", remoteGitDir.toUri().toString());
    runGit(seed, "push", "--set-upstream", "origin", "main");
  }

  private String runGit(Path directory, String... args) throws Exception {
    ProcessBuilder builder = new ProcessBuilder();
    builder.command(concat("git", args));
    builder.directory(directory.toFile());
    builder.redirectErrorStream(true);
    builder.environment().put("GIT_TERMINAL_PROMPT", "0");
    Process process = builder.start();
    byte[] output = process.getInputStream().readAllBytes();
    if (!process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) {
      process.destroyForcibly();
      throw new IllegalStateException("git command timed out");
    }
    String stdout = new String(output, StandardCharsets.UTF_8);
    if (process.exitValue() != 0) {
      throw new IllegalStateException("git command failed: " + stdout);
    }
    return stdout;
  }

  private List<String> concat(String first, String... rest) {
    List<String> command = new java.util.ArrayList<>();
    command.add(first);
    command.addAll(java.util.Arrays.asList(rest));
    return command;
  }

  private GHRef toGhRef(String ref) throws Exception {
    String sha = resolveRemoteSha(ref);
    if (sha == null) {
      throw new GHFileNotFoundException(ref + " not found");
    }
    GHRef.GHObject object = mock(GHRef.GHObject.class);
    when(object.getSha()).thenReturn(sha);
    GHRef ghRef = mock(GHRef.class);
    when(ghRef.getObject()).thenReturn(object);
    return ghRef;
  }

  private void updateRemoteRef(String ref, String sha) throws Exception {
    runBareGit("update-ref", ref, sha);
  }

  private String resolveRemoteSha(String ref) throws Exception {
    String fullRef = ref.startsWith("refs/") ? ref : "refs/" + ref;
    ProcessBuilder builder = new ProcessBuilder();
    builder.command(
        "git", "--git-dir", remoteGitDir.toString(), "rev-parse", "--verify", fullRef);
    builder.redirectErrorStream(true);
    Process process = builder.start();
    byte[] output = process.getInputStream().readAllBytes();
    if (!process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS) || process.exitValue() != 0) {
      return null;
    }
    return new String(output, StandardCharsets.UTF_8).trim();
  }

  private String runBareGit(String... args) throws Exception {
    List<String> command = new java.util.ArrayList<>();
    command.add("git");
    command.add("--git-dir");
    command.add(remoteGitDir.toString());
    command.addAll(java.util.Arrays.asList(args));
    ProcessBuilder builder = new ProcessBuilder(command);
    builder.redirectErrorStream(true);
    Process process = builder.start();
    byte[] output = process.getInputStream().readAllBytes();
    if (!process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) {
      process.destroyForcibly();
      throw new IllegalStateException("git --git-dir command timed out");
    }
    if (process.exitValue() != 0) {
      throw new IllegalStateException("git --git-dir command failed: " + new String(output, StandardCharsets.UTF_8));
    }
    return new String(output, StandardCharsets.UTF_8).trim();
  }
}
