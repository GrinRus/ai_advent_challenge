package com.aiadvent.mcp.backend.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aiadvent.mcp.backend.config.GitHubBackendProperties;
import com.aiadvent.mcp.backend.github.GitHubRepositoryService.ApprovePullRequestInput;
import com.aiadvent.mcp.backend.github.GitHubRepositoryService.ApprovePullRequestResult;
import com.aiadvent.mcp.backend.github.GitHubRepositoryService.CommitAuthor;
import com.aiadvent.mcp.backend.github.GitHubRepositoryService.CommitFileChange;
import com.aiadvent.mcp.backend.github.GitHubRepositoryService.CommitWorkspaceDiffInput;
import com.aiadvent.mcp.backend.github.GitHubRepositoryService.CommitWorkspaceDiffResult;
import com.aiadvent.mcp.backend.github.GitHubRepositoryService.CreateBranchInput;
import com.aiadvent.mcp.backend.github.GitHubRepositoryService.CreateBranchResult;
import com.aiadvent.mcp.backend.github.GitHubRepositoryService.MergeMethod;
import com.aiadvent.mcp.backend.github.GitHubRepositoryService.MergePullRequestInput;
import com.aiadvent.mcp.backend.github.GitHubRepositoryService.MergePullRequestResult;
import com.aiadvent.mcp.backend.github.GitHubRepositoryService.OpenPullRequestInput;
import com.aiadvent.mcp.backend.github.GitHubRepositoryService.OpenPullRequestResult;
import com.aiadvent.mcp.backend.github.GitHubRepositoryService.PushBranchInput;
import com.aiadvent.mcp.backend.github.GitHubRepositoryService.PushBranchResult;
import com.aiadvent.mcp.backend.github.GitHubRepositoryService.RepositoryRef;
import com.aiadvent.mcp.backend.github.rag.RepoRagIndexScheduler;
import com.aiadvent.mcp.backend.github.workspace.TempWorkspaceService;
import com.aiadvent.mcp.backend.github.workspace.TempWorkspaceService.CreateWorkspaceRequest;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kohsuke.github.GHCommit;
import org.kohsuke.github.GHCommitPointer;
import org.kohsuke.github.GHOrganization;
import org.kohsuke.github.GHObject;
import org.kohsuke.github.GHPullRequest;
import org.kohsuke.github.GHPullRequestReview;
import org.kohsuke.github.GHPullRequestReviewBuilder;
import org.kohsuke.github.GHPullRequestReviewEvent;
import org.kohsuke.github.GHPullRequestReviewState;
import org.kohsuke.github.GHRef;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GHCompare;
import org.kohsuke.github.GHTeam;
import org.kohsuke.github.GHUser;
import org.kohsuke.github.GitHub;
import org.kohsuke.github.GHFileNotFoundException;
import org.mockito.stubbing.Answer;

class GitHubWriteFlowIntegrationTests {

  private static final String OWNER = "acme";
  private static final String REPOSITORY = "demo";
  private static final String FULL_NAME = OWNER + "/" + REPOSITORY;

  @TempDir Path tempDir;

  private Path remoteGitDir;
  private TempWorkspaceService workspaceService;
  private GitHubRepositoryService repositoryService;
  private String workspaceId;
  private Path workspacePath;
  private GitHubClientExecutor executor;
  private GitHub githubMock;
  private GHRepository repoMock;
  private GitHubTokenManager tokenManager;
  private Map<String, String> branchRefs;
  private Map<Integer, PullRequestState> pullRequests;
  private AtomicInteger prSequence;

  @BeforeEach
  void setUp() throws Exception {
    remoteGitDir = tempDir.resolve("remote.git");
    runGit(tempDir, "init", "--bare", remoteGitDir.getFileName().toString());

    GitHubBackendProperties properties = new GitHubBackendProperties();
    properties.setWorkspaceRoot(tempDir.resolve("workspaces").toString());
    properties.setCommitDiffMaxBytes(2L * 1024 * 1024);
    properties.setCloneBaseUrl(remoteGitDir.toUri().toString());

    workspaceService = new TempWorkspaceService(properties, null);
    workspaceService.afterPropertiesSet();

    var workspace =
        workspaceService.createWorkspace(
            new CreateWorkspaceRequest(FULL_NAME, "heads/main", null));
    workspaceId = workspace.workspaceId();
    workspacePath = workspace.path();

    executor = mock(GitHubClientExecutor.class);
    githubMock = mock(GitHub.class);
    repoMock = mock(GHRepository.class);
    tokenManager = mock(GitHubTokenManager.class);
    branchRefs = new ConcurrentHashMap<>();
    pullRequests = new ConcurrentHashMap<>();
    prSequence = new AtomicInteger(41);

    initializeLocalRepository();

    configureGitHubMocks();

    RepoRagIndexScheduler indexScheduler = mock(RepoRagIndexScheduler.class);
    repositoryService =
        new GitHubRepositoryService(
            executor, properties, workspaceService, indexScheduler, tokenManager, null);
  }

  @AfterEach
  void tearDown() throws Exception {
    workspaceService.destroy();
  }

  @Test
  void endToEndPositiveFlow() throws Exception {
    RepositoryRef repoRef = new RepositoryRef(OWNER, REPOSITORY, "heads/main");

    CreateBranchResult branchResult =
        repositoryService.createBranch(
            new CreateBranchInput(repoRef, workspaceId, "feature/demo", null));
    assertThat(branchResult.branchRef()).isEqualTo("refs/heads/feature/demo");

    Files.writeString(workspacePath.resolve("app.txt"), "hello-world\n", StandardCharsets.UTF_8);

    CommitWorkspaceDiffResult commitResult =
        repositoryService.commitWorkspaceDiff(
            new CommitWorkspaceDiffInput(
                workspaceId,
                "feature/demo",
                new CommitAuthor("CI Bot", "ci@example.com"),
                "feat: add app file"));

    assertThat(commitResult.branchName()).isEqualTo("feature/demo");
    assertThat(commitResult.files())
        .extracting(CommitFileChange::path)
        .contains("app.txt");
    String status = runGit(workspacePath, "status", "--short");
    assertThat(status.trim()).as("workspace status after commit").isEmpty();

    PushBranchResult pushResult =
        repositoryService.pushBranch(
            new PushBranchInput(repoRef, workspaceId, "feature/demo", false));

    String remoteFeatureSha = resolveRemoteRef("heads/feature/demo");
    assertThat(remoteFeatureSha).isEqualTo(commitResult.commitSha());
    assertThat(pushResult.localHeadSha()).isEqualTo(pushResult.remoteHeadSha());

    OpenPullRequestResult prResult =
        repositoryService.openPullRequest(
            new OpenPullRequestInput(
                repoRef,
                "feature/demo",
                "main",
                "Add hello world",
                "Created by integration test",
                List.of("reviewer1"),
                List.of("platform"),
                false));

    assertThat(prResult.pullRequestNumber()).isGreaterThan(0);

    ApprovePullRequestResult approveResult =
        repositoryService.approvePullRequest(
            new ApprovePullRequestInput(repoRef, prResult.pullRequestNumber(), "LGTM"));
    assertThat(approveResult.state()).isEqualTo(GHPullRequestReviewState.APPROVED.name());

    MergePullRequestResult mergeResult =
        repositoryService.mergePullRequest(
            new MergePullRequestInput(
                repoRef, prResult.pullRequestNumber(), MergeMethod.SQUASH, null, null));

    assertThat(mergeResult.merged()).isTrue();
    assertThat(mergeResult.mergeSha()).isNotBlank();

    String remoteMainSha = resolveRemoteRef("heads/main");
    assertThat(remoteMainSha).isEqualTo(mergeResult.mergeSha());
  }

  @Test
  void createBranchRecoversWorkspaceWithoutGitMetadata() throws Exception {
    RepositoryRef repoRef = new RepositoryRef(OWNER, REPOSITORY, "heads/main");
    Path gitDir = workspacePath.resolve(".git");
    deleteRecursively(gitDir);
    assertThat(Files.exists(gitDir)).isFalse();

    CreateBranchResult branchResult =
        repositoryService.createBranch(
            new CreateBranchInput(repoRef, workspaceId, "feature/reinit", null));

    assertThat(branchResult.branchRef()).isEqualTo("refs/heads/feature/reinit");
    assertThat(Files.isDirectory(gitDir)).isTrue();
    String status = runGit(workspacePath, "status", "--short");
    assertThat(status.trim()).isEmpty();
  }

  @Test
  void pushBranchRejectsForceFlag() {
    RepositoryRef repoRef = new RepositoryRef(OWNER, REPOSITORY, "heads/main");

    assertThatThrownBy(
            () ->
                repositoryService.pushBranch(
                    new PushBranchInput(repoRef, workspaceId, "main", true)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Force push is disabled");
  }

  private void initializeLocalRepository() throws Exception {
    runGit(workspacePath, "init");
    runGit(workspacePath, "config", "user.name", "Test Bot");
    runGit(workspacePath, "config", "user.email", "test@example.com");
    String remoteUri = remoteGitDir.toUri().toString();
    runGit(workspacePath, "remote", "add", "origin", remoteUri);

    Files.writeString(workspacePath.resolve("README.md"), "initial\n", StandardCharsets.UTF_8);
    Files.writeString(workspacePath.resolve(".gitignore"), ".workspace.json\n", StandardCharsets.UTF_8);
    runGit(workspacePath, "add", "README.md", ".gitignore");
    runGit(workspacePath, "commit", "-m", "Initial commit");
    runGit(workspacePath, "branch", "-M", "main");
    runGit(workspacePath, "push", "origin", "main");

    String headSha = resolveLocalRef("HEAD");
    branchRefs.put("heads/main", headSha);
    workspaceService.updateWorkspace(workspaceId, null, headSha, List.of("README.md"));
  }

  private void deleteRecursively(Path target) throws IOException {
    if (!Files.exists(target)) {
      return;
    }
    Files.walkFileTree(
        target,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
            Files.deleteIfExists(file);
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
            Files.deleteIfExists(dir);
            return FileVisitResult.CONTINUE;
          }
        });
  }

  private void configureGitHubMocks() throws Exception {
    lenient().when(executor.execute(any())).thenAnswer(applyFunction());
    lenient().doAnswer(applyConsumer()).when(executor).executeVoid(any());

    when(githubMock.getRepository(FULL_NAME)).thenReturn(repoMock);
    when(tokenManager.currentToken()).thenReturn("test-token");

    when(repoMock.hasPushAccess()).thenReturn(true);

    lenient()
        .when(repoMock.getRef(anyString()))
        .thenAnswer(
            invocation -> {
              String ref = invocation.getArgument(0);
              String sha = resolveBranch(ref);
              if (sha == null) {
                throw new GHFileNotFoundException("Not Found: " + ref);
              }
              GHRef ghRef = mock(GHRef.class);
              org.kohsuke.github.GHRef.GHObject refObject =
                  mock(org.kohsuke.github.GHRef.GHObject.class);
              when(refObject.getSha()).thenReturn(sha);
              when(ghRef.getObject()).thenReturn(refObject);
              return ghRef;
            });

    lenient()
        .doAnswer(
            invocation -> {
              String refName = invocation.getArgument(0);
              String sha = invocation.getArgument(1);
              String normalized =
                  refName.startsWith("refs/") ? refName.substring("refs/".length()) : refName;
              branchRefs.put(normalized, sha);
              runGitWithGitDir(remoteGitDir, "update-ref", "refs/" + normalized, sha);
              return null;
            })
        .when(repoMock)
        .createRef(anyString(), anyString());

    lenient()
        .when(repoMock.createPullRequest(
            anyString(), anyString(), anyString(), anyString(), anyBoolean()))
        .thenAnswer(this::createPullRequest);

    lenient()
        .when(repoMock.getPullRequest(anyInt()))
        .then(invocation -> pullRequests.get(invocation.getArgument(0)).pullRequest());

    lenient()
        .when(repoMock.getCompare(anyString(), anyString()))
        .thenAnswer(
            invocation -> {
              GHCompare compare = mock(GHCompare.class);
              GHCommit.File file = mock(GHCommit.File.class);
              when(compare.getFiles()).thenReturn(new GHCommit.File[] {file});
              when(compare.getTotalCommits()).thenReturn(1);
              return compare;
            });

    lenient().when(githubMock.getUser(anyString())).thenAnswer(this::resolveUser);

    GHOrganization organization = mock(GHOrganization.class);
    when(githubMock.getOrganization(OWNER)).thenReturn(organization);
    lenient()
        .when(organization.getTeamBySlug(anyString()))
        .thenAnswer(
            invocation -> {
              GHTeam team = mock(GHTeam.class);
              when(team.getSlug()).thenReturn(invocation.getArgument(0));
              return team;
            });
  }

  private Answer<Object> applyFunction() {
    return invocation -> {
      @SuppressWarnings("unchecked")
      java.util.function.Function<GitHub, ?> function =
          (java.util.function.Function<GitHub, ?>) invocation.getArgument(0);
      return function.apply(githubMock);
    };
  }

  private Answer<Object> applyConsumer() {
    return invocation -> {
      @SuppressWarnings("unchecked")
      java.util.function.Consumer<GitHub> consumer =
          (java.util.function.Consumer<GitHub>) invocation.getArgument(0);
      consumer.accept(githubMock);
      return null;
    };
  }

  private Object resolveUser(org.mockito.invocation.InvocationOnMock invocation) {
    GHUser user = mock(GHUser.class);
    when(user.getLogin()).thenReturn(invocation.getArgument(0));
    return user;
  }

  private Object createPullRequest(org.mockito.invocation.InvocationOnMock invocation)
      throws Exception {
    String title = invocation.getArgument(0);
    String headSpec = invocation.getArgument(1);
    String baseBranch = invocation.getArgument(2);
    String body = invocation.getArgument(3);
    boolean draft = invocation.getArgument(4);

    String[] headParts = headSpec.split(":");
    String headBranch = headParts[headParts.length - 1];
    String headSha = resolveBranch("heads/" + headBranch);
    String baseSha = resolveBranch("heads/" + baseBranch);

    int number = prSequence.incrementAndGet();
    PullRequestState state =
        new PullRequestState(number, headBranch, baseBranch, headSha, baseSha, draft);
    pullRequests.put(number, state);

    GHPullRequest pullRequest = mock(GHPullRequest.class);
    state.setPullRequest(pullRequest);

    when(pullRequest.getNumber()).thenReturn(number);
    when(pullRequest.getTitle()).thenReturn(title);
    when(pullRequest.getHtmlUrl()).thenReturn(new URL("https://example.com/pr/" + number));
    when(pullRequest.getBody()).thenReturn(body);
    when(pullRequest.isDraft()).thenReturn(draft);
    when(pullRequest.getMergeable()).thenReturn(Boolean.TRUE);
    when(pullRequest.getMergeableState()).thenAnswer(inv -> state.mergeableState());
    when(pullRequest.getMergeCommitSha()).thenAnswer(inv -> state.mergeSha());
    when(pullRequest.getMergedAt()).thenAnswer(inv -> state.mergedAt());
    when(pullRequest.isMerged()).thenAnswer(inv -> state.merged());

    configureCommitPointers(state, pullRequest);
    configureReviewBuilder(state, pullRequest);

    lenient()
        .doAnswer(inv -> null)
        .when(pullRequest)
        .requestReviewers(anyList());
    lenient()
        .doAnswer(inv -> null)
        .when(pullRequest)
        .requestTeamReviewers(anyList());
    lenient()
        .doAnswer(
            inv -> {
              state.refresh();
              return null;
            })
        .when(pullRequest)
        .refresh();

    lenient()
        .doAnswer(
            inv -> {
              state.merge(resolveBranch("heads/" + headBranch));
              runGitWithGitDir(remoteGitDir, "update-ref", "refs/heads/" + baseBranch, state.mergeSha());
              return null;
            })
        .when(pullRequest)
        .merge(anyString(), anyString(), any(GHPullRequest.MergeMethod.class));

    return pullRequest;
  }

  private void configureCommitPointers(
      PullRequestState state, GHPullRequest pullRequest) throws Exception {
    GHCommitPointer headPointer = mock(GHCommitPointer.class);
    when(headPointer.getSha()).thenAnswer(inv -> state.headSha());
    when(headPointer.getLabel())
        .thenReturn(OWNER + ":" + state.headBranch().replace("heads/", ""));
    when(pullRequest.getHead()).thenReturn(headPointer);

    GHCommitPointer basePointer = mock(GHCommitPointer.class);
    when(basePointer.getSha()).thenAnswer(inv -> state.baseSha());
    when(basePointer.getLabel()).thenReturn(OWNER + ":" + state.baseBranch());
    when(pullRequest.getBase()).thenReturn(basePointer);
  }

  private void configureReviewBuilder(PullRequestState state, GHPullRequest pullRequest) {
    lenient()
        .when(pullRequest.createReview())
        .thenAnswer(
            inv -> {
              GHPullRequestReviewBuilder builder = mock(GHPullRequestReviewBuilder.class);
              lenient()
                  .when(builder.event(any(GHPullRequestReviewEvent.class)))
                  .thenReturn(builder);
              lenient().when(builder.body(anyString())).thenReturn(builder);
              when(builder.create())
                  .thenAnswer(
                      createInvocation -> {
                        try {
                          GHPullRequestReview review = new GHPullRequestReview();
                          setLongField(review, org.kohsuke.github.GHObject.class, "id", state.number() * 100L);
                          setField(review, GHPullRequestReview.class, "state", GHPullRequestReviewState.APPROVED);
                          setField(
                              review,
                              GHPullRequestReview.class,
                              "submitted_at",
                              Instant.now().toString());
                          state.setReview(review);
                          return review;
                        } catch (Exception ex) {
                          throw new RuntimeException(ex);
                        }
                      });
              return builder;
            });
  }

  private String resolveBranch(String ref) throws Exception {
    String normalized = ref.startsWith("refs/") ? ref.substring("refs/".length()) : ref;
    String sha = resolveRemoteRef(normalized);
    if (sha != null) {
      branchRefs.put(normalized, sha);
      return sha;
    }
    return branchRefs.get(normalized);
  }

  private String resolveRemoteRef(String ref) throws Exception {
    ProcessResult result =
        runGitRaw(
            tempDir,
            "git",
            "--git-dir",
            remoteGitDir.toString(),
            "rev-parse",
            "refs/" + ref);
    if (result.exitCode() != 0) {
      return null;
    }
    return result.output().trim();
  }

  private String resolveLocalRef(String ref) throws Exception {
    return runGit(workspacePath, "rev-parse", ref).trim();
  }

  private void runGitWithGitDir(Path gitDir, String... args) throws Exception {
    List<String> command = new ArrayList<>();
    command.add("git");
    command.add("--git-dir");
    command.add(gitDir.toString());
    for (String arg : args) {
      command.add(arg);
    }
    ProcessResult result = runGitRaw(tempDir, command.toArray(String[]::new));
    if (result.exitCode() != 0) {
      throw new IllegalStateException("git command failed: " + String.join(" ", command));
    }
  }

  private String runGit(Path directory, String... args) throws Exception {
    List<String> command = new ArrayList<>();
    command.add("git");
    for (String arg : args) {
      command.add(arg);
    }
    ProcessResult result = runGitRaw(directory, command.toArray(String[]::new));
    if (result.exitCode() != 0) {
      throw new IllegalStateException(
          "git command failed: " + String.join(" ", command) + "\n" + result.output());
    }
    return result.output();
  }

  private ProcessResult runGitRaw(Path directory, String... command) throws IOException, InterruptedException {
    ProcessBuilder builder = new ProcessBuilder(command);
    builder.directory(directory.toFile());
    builder.redirectErrorStream(true);
    builder.environment().put("GIT_TERMINAL_PROMPT", "0");
    builder.environment().putIfAbsent("LC_ALL", "C");
    Process process = builder.start();
    byte[] bytes = process.getInputStream().readAllBytes();
    int exit = process.waitFor();
    return new ProcessResult(exit, new String(bytes, StandardCharsets.UTF_8));
  }

  private record ProcessResult(int exitCode, String output) {}

  private static void setField(Object target, Class<?> owner, String name, Object value)
      throws NoSuchFieldException, IllegalAccessException {
    Field field = owner.getDeclaredField(name);
    field.setAccessible(true);
    field.set(target, value);
  }

  private static void setLongField(Object target, Class<?> owner, String name, long value)
      throws NoSuchFieldException, IllegalAccessException {
    Field field = owner.getDeclaredField(name);
    field.setAccessible(true);
    field.setLong(target, value);
  }

  private static final class PullRequestState {
    private final int number;
    private final String headBranch;
    private final String baseBranch;
    private final boolean draft;
    private final Instant createdAt;
    private String headSha;
    private String baseSha;
    private boolean merged;
    private String mergeSha;
    private Date mergedAt;
    private GHPullRequest pullRequest;
    private GHPullRequestReview review;
    private String mergeableState = "clean";

    PullRequestState(
        int number,
        String headBranch,
        String baseBranch,
        String headSha,
        String baseSha,
        boolean draft) {
      this.number = number;
      this.headBranch = headBranch;
      this.baseBranch = baseBranch;
      this.headSha = headSha;
      this.baseSha = baseSha;
      this.draft = draft;
      this.createdAt = Instant.now();
    }

    int number() {
      return number;
    }

    String headBranch() {
      return headBranch;
    }

    String baseBranch() {
      return baseBranch;
    }

    String headSha() {
      return headSha;
    }

    String baseSha() {
      return baseSha;
    }

    boolean merged() {
      return merged;
    }

    String mergeSha() {
      return mergeSha;
    }

    Date mergedAt() {
      return mergedAt;
    }

    String mergeableState() {
      return mergeableState;
    }

    GHPullRequest pullRequest() {
      return pullRequest;
    }

    void setPullRequest(GHPullRequest pullRequest) {
      this.pullRequest = pullRequest;
    }

    void setReview(GHPullRequestReview review) {
      this.review = review;
    }

    void merge(String newBaseSha) {
      this.merged = true;
      this.mergeSha = newBaseSha;
      this.baseSha = newBaseSha;
      this.mergedAt = Date.from(Instant.now());
    }

    void refresh() {}
  }
}
