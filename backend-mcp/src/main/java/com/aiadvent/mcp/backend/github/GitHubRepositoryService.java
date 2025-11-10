package com.aiadvent.mcp.backend.github;

import com.aiadvent.mcp.backend.config.GitHubBackendProperties;
import com.aiadvent.mcp.backend.github.rag.RepoRagIndexScheduler;
import com.aiadvent.mcp.backend.github.workspace.TempWorkspaceService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.BufferedInputStream;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.regex.Pattern;
import org.kohsuke.github.GHCheckRun;
import org.kohsuke.github.GHCommit;
import org.kohsuke.github.GHCommitStatus;
import org.kohsuke.github.GHContent;
import org.kohsuke.github.GHDirection;
import org.kohsuke.github.GHCompare;
import org.kohsuke.github.GHFileNotFoundException;
import org.kohsuke.github.GHIssueComment;
import org.kohsuke.github.GHIssueState;
import org.kohsuke.github.GHLabel;
import org.kohsuke.github.GHMilestone;
import org.kohsuke.github.GHPullRequest;
import org.kohsuke.github.GHPullRequestFileDetail;
import org.kohsuke.github.GHPullRequestQueryBuilder;
import org.kohsuke.github.GHPullRequestReview;
import org.kohsuke.github.GHPullRequestReviewBuilder;
import org.kohsuke.github.GHPullRequestReviewComment;
import org.kohsuke.github.GHPullRequestReviewCommentBuilder;
import org.kohsuke.github.GHPullRequestReviewEvent;
import org.kohsuke.github.GHPullRequestReviewState;
import org.kohsuke.github.GHRef;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GHTeam;
import org.kohsuke.github.GHTree;
import org.kohsuke.github.GHTreeEntry;
import org.kohsuke.github.GHUser;
import org.kohsuke.github.GHOrganization;
import org.kohsuke.github.HttpException;
import org.kohsuke.github.PagedIterable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class GitHubRepositoryService {

  private static final Logger log = LoggerFactory.getLogger(GitHubRepositoryService.class);
  private static final int DEFAULT_PR_PAGE_LIMIT = 20;
  private static final int MAX_PR_PAGE_LIMIT = 50;
  private static final int DEFAULT_COMMENT_LIMIT = 50;
  private static final int MAX_COMMENT_LIMIT = 200;
  private static final int DEFAULT_CHECK_LIMIT = 50;
  private static final int MAX_CHECK_LIMIT = 200;
  private static final long MAX_DIFF_BYTES = 1_048_576L; // 1 MiB safeguard
  private static final int COMMENT_DEDUP_LIMIT = 50;
  private static final int REVIEW_DEDUP_LIMIT = 20;
  private static final int MAX_API_ERROR_DETAIL = 500;
  private static final int MAX_BRANCH_NAME_LENGTH = 255;
  private static final Pattern INVALID_BRANCH_PATTERN = Pattern.compile("[\\s~^:?*\\[\\\\]");
  private static final Pattern INVALID_BRANCH_COMPONENT = Pattern.compile("(^\\.|\\.\\.|@\\{|//|\\.lock$)");
  private static final String WORKSPACE_METADATA_FILE = ".workspace.json";
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private final GitHubClientExecutor executor;
  private final GitHubBackendProperties properties;
  private final TempWorkspaceService workspaceService;
  private final RepoRagIndexScheduler repoRagIndexScheduler;
  private final GitHubTokenManager tokenManager;
  private final MeterRegistry meterRegistry;
  private final Timer fetchTimer;
  private final Counter fetchSuccessCounter;
  private final Counter fetchFailureCounter;
  private final DistributionSummary downloadSizeSummary;
  private final DistributionSummary workspaceSizeSummary;

  private final Map<String, CachedTree> treeCache = new ConcurrentHashMap<>();
  private final Map<String, CachedFile> fileCache = new ConcurrentHashMap<>();

  GitHubRepositoryService(
      GitHubClientExecutor executor,
      GitHubBackendProperties properties,
      TempWorkspaceService workspaceService,
      RepoRagIndexScheduler repoRagIndexScheduler,
      GitHubTokenManager tokenManager,
      @Nullable MeterRegistry meterRegistry) {
    this.executor = Objects.requireNonNull(executor, "executor");
    this.properties = Objects.requireNonNull(properties, "properties");
    this.workspaceService = Objects.requireNonNull(workspaceService, "workspaceService");
    this.repoRagIndexScheduler =
        Objects.requireNonNull(repoRagIndexScheduler, "repoRagIndexScheduler");
    this.tokenManager = Objects.requireNonNull(tokenManager, "tokenManager");
    MeterRegistry registry = meterRegistry;
    if (registry == null) {
      registry = new SimpleMeterRegistry();
    }
    this.meterRegistry = registry;
    this.fetchTimer = this.meterRegistry.timer("github_repository_fetch_duration");
    this.fetchSuccessCounter =
        this.meterRegistry.counter("github_repository_fetch_success_total");
    this.fetchFailureCounter =
        this.meterRegistry.counter("github_repository_fetch_failure_total");
    this.downloadSizeSummary =
        this.meterRegistry.summary("github_repository_fetch_download_bytes");
    this.workspaceSizeSummary =
        this.meterRegistry.summary("github_repository_fetch_workspace_bytes");
  }

  ListRepositoryTreeResult listRepositoryTree(ListRepositoryTreeInput input) {
    RepositoryRef repository = normalizeRepository(input.repository());
    String normalizedPath = normalizePath(input.path());
    boolean recursive = Boolean.TRUE.equals(input.recursive());
    int requestedDepth = Optional.ofNullable(input.maxDepth()).orElse(properties.getTreeMaxDepth());
    int maxEntries = Optional.ofNullable(input.maxEntries()).orElse(properties.getTreeMaxEntries());
    maxEntries = Math.max(1, Math.min(maxEntries, properties.getTreeMaxEntries()));

    TreeData treeData = loadTree(repository, requestedDepth);
    FilteredTree filtered =
        filterTree(treeData.entries(), normalizedPath, recursive, maxEntries);
    boolean truncated = treeData.truncated() || filtered.reachedLimit();

    return new ListRepositoryTreeResult(
        repository, treeData.resolvedRef(), filtered.entries(), truncated);
  }

  ReadFileResult readFile(ReadFileInput input) {
    RepositoryRef repository = normalizeRepository(input.repository());
    String normalizedPath = normalizePath(input.path());
    if (!StringUtils.hasText(normalizedPath)) {
      throw new IllegalArgumentException("File path must not be blank");
    }

    FileData data = loadFile(repository, normalizedPath);
    return new ReadFileResult(repository, data.ref(), data.file());
  }

  FetchRepositoryResult fetchRepository(FetchRepositoryInput input) {
    Objects.requireNonNull(input, "input");
    RepositoryRef repository = normalizeRepository(input.repository());
    NormalizedFetchOptions options = normalizeFetchOptions(repository, input.options());
    String requestId = StringUtils.hasText(input.requestId()) ? input.requestId().trim() : null;

    TempWorkspaceService.Workspace workspace =
        workspaceService.createWorkspace(
            new TempWorkspaceService.CreateWorkspaceRequest(
                repository.fullName(), repository.ref(), requestId));

    Instant startedAt = Instant.now();
    try {
      FetchExecutionOutcome outcome = performFetch(repository, workspace, options);
      long workspaceSize = calculateWorkspaceSize(workspace.path());
      workspaceService.ensureWithinLimit(workspaceSize);
      List<String> keyFiles =
          options.detectKeyFiles() ? collectKeyFiles(workspace.path()) : List.of();
      TempWorkspaceService.Workspace updated =
          workspaceService.updateWorkspace(
              workspace.workspaceId(), workspaceSize, outcome.commitSha(), keyFiles);
      Duration duration = Duration.between(startedAt, outcome.completedAt());
      fetchTimer.record(duration);
      fetchSuccessCounter.increment();
      if (outcome.downloadedBytes() > 0) {
        downloadSizeSummary.record((double) outcome.downloadedBytes());
      }
      if (workspaceSize > 0) {
        workspaceSizeSummary.record((double) workspaceSize);
      }
      FetchRepositoryResult result =
          new FetchRepositoryResult(
              repository,
              updated.workspaceId(),
              updated.path(),
              outcome.resolvedRef(),
              outcome.commitSha(),
              outcome.downloadedBytes(),
              workspaceSize,
              duration,
              outcome.strategy(),
              keyFiles,
              outcome.completedAt());
      scheduleRagIndexing(result);
      return result;
    } catch (RuntimeException ex) {
      fetchFailureCounter.increment();
      fetchTimer.record(Duration.between(startedAt, Instant.now()));
      workspaceService.deleteWorkspace(workspace.workspaceId());
      throw ex;
    }
  }

  private void scheduleRagIndexing(FetchRepositoryResult result) {
    RepositoryRef repository = result.repository();
    try {
      repoRagIndexScheduler.scheduleIndexing(
          repository.owner(),
          repository.name(),
          result.workspaceId(),
          result.resolvedRef(),
          result.commitSha(),
          result.workspaceSizeBytes(),
          result.fetchedAt());
    } catch (Exception ex) {
      log.warn(
          "Unable to enqueue repo RAG indexing for {}/{}: {}",
          repository.owner(),
          repository.name(),
          ex.getMessage(),
          ex);
    }
  }

  ListPullRequestsResult listPullRequests(ListPullRequestsInput input) {
    RepositoryRef repository = normalizeRepository(input.repository());
    int limit =
        Math.max(
            1,
            Math.min(
                Optional.ofNullable(input.limit()).orElse(DEFAULT_PR_PAGE_LIMIT), MAX_PR_PAGE_LIMIT));

    PullRequestListData data =
        executor.execute(github -> fetchPullRequests(github, repository, input, limit));

    return new ListPullRequestsResult(repository, data.pullRequests(), data.truncated());
  }

  PullRequestDetailsResult getPullRequest(GetPullRequestInput input) {
    RepositoryRef repository = normalizeRepository(input.repository());
    if (input.number() == null || input.number() <= 0) {
      throw new IllegalArgumentException("pullRequestNumber must be positive");
    }

    PullRequestDetailsData data =
        executor.execute(github -> fetchPullRequestDetails(github, repository, input.number()));
    return new PullRequestDetailsResult(repository, data.pullRequest());
  }

  PullRequestDiffResult getPullRequestDiff(GetPullRequestDiffInput input) {
    RepositoryRef repository = normalizeRepository(input.repository());
    if (input.number() == null || input.number() <= 0) {
      throw new IllegalArgumentException("pullRequestNumber must be positive");
    }
    long maxBytes =
        Optional.ofNullable(input.maxBytes())
            .filter(value -> value > 0)
            .map(Long::valueOf)
            .orElseGet(
                () -> Optional.ofNullable(properties.getFileMaxSizeBytes()).orElse(MAX_DIFF_BYTES));

    PullRequestDiffData data =
        executor.execute(github -> fetchPullRequestDiff(github, repository, input.number(), maxBytes));
    return new PullRequestDiffResult(repository, data.ref(), data.diff(), data.truncated());
  }

  PullRequestCommentsResult listPullRequestComments(ListPullRequestCommentsInput input) {
    RepositoryRef repository = normalizeRepository(input.repository());
    if (input.number() == null || input.number() <= 0) {
      throw new IllegalArgumentException("pullRequestNumber must be positive");
    }
    int issueLimit =
        Math.max(
            0,
            Math.min(
                Optional.ofNullable(input.issueCommentLimit()).orElse(DEFAULT_COMMENT_LIMIT),
                MAX_COMMENT_LIMIT));
    int reviewLimit =
        Math.max(
            0,
            Math.min(
                Optional.ofNullable(input.reviewCommentLimit()).orElse(DEFAULT_COMMENT_LIMIT),
                MAX_COMMENT_LIMIT));

    PullRequestCommentsData data =
        executor.execute(
            github -> fetchPullRequestComments(github, repository, input.number(), issueLimit, reviewLimit));
    return new PullRequestCommentsResult(repository, data.issueComments(), data.reviewComments(), data.truncatedIssue(), data.truncatedReview());
  }

  PullRequestChecksResult listPullRequestChecks(ListPullRequestChecksInput input) {
    RepositoryRef repository = normalizeRepository(input.repository());
    if (input.number() == null || input.number() <= 0) {
      throw new IllegalArgumentException("pullRequestNumber must be positive");
    }
    int checkRunLimit =
        Math.max(
            0,
            Math.min(
                Optional.ofNullable(input.checkRunLimit()).orElse(DEFAULT_CHECK_LIMIT), MAX_CHECK_LIMIT));
    int statusLimit =
        Math.max(
            0,
            Math.min(
                Optional.ofNullable(input.statusLimit()).orElse(DEFAULT_CHECK_LIMIT), MAX_CHECK_LIMIT));

    PullRequestChecksData data =
        executor.execute(
            github -> fetchPullRequestChecks(github, repository, input.number(), checkRunLimit, statusLimit));
    return new PullRequestChecksResult(
        repository,
        data.pullRequestNumber(),
        data.headSha(),
        data.overallStatus(),
        data.checkRuns(),
        data.statuses(),
        data.truncatedCheckRuns(),
        data.truncatedStatuses());
  }

  CreateBranchResult createBranch(CreateBranchInput input) {
    Objects.requireNonNull(input, "input");
    RepositoryRef repository = normalizeRepository(input.repository());
    String branchName = validateBranchName(input.branchName());
    TempWorkspaceService.Workspace workspace = requireWorkspace(input.workspaceId());
    ensureWorkspaceMatches(workspace, repository);

    Path workspacePath = workspace.path();
    String token = tokenManager.currentToken();
    String authHeader = authorizationHeader(token);
    Duration gitTimeout = Duration.ofSeconds(60);
    String sourceSha =
        resolveSourceSha(workspace, input.sourceSha(), workspacePath, authHeader, token);

    ensureGitRepositoryInitialized(
        repository, workspace, workspacePath, sourceSha, gitTimeout, authHeader, token);

    executor.executeVoid(
        github -> {
          try {
            GHRepository repo = github.getRepository(repository.fullName());
            ensurePushAccess(repo, repository);
            ensureRemoteBranchAbsent(repo, branchName, repository);
            repo.createRef("refs/heads/" + branchName, sourceSha);
          } catch (HttpException ex) {
            if (ex.getResponseCode() == 422 || ex.getResponseCode() == 409) {
              throw new GitHubClientException(
                  "Branch %s already exists in %s"
                      .formatted(branchName, repository.fullName()),
                  ex);
            }
            throw new GitHubClientException(
                "Failed to create branch %s in %s (HTTP %d)"
                    .formatted(branchName, repository.fullName(), ex.getResponseCode()),
                ex);
          } catch (IOException ex) {
            throw new GitHubClientException(
                "Failed to create branch %s in %s"
                    .formatted(branchName, repository.fullName()),
                ex);
          }
        });

    runGitCommand(
        workspacePath, gitTimeout, authHeader, token, "git", "fetch", "--prune", "origin");
    if (branchExistsLocally(workspacePath, branchName)) {
      throw new IllegalStateException(
          "Local branch already exists: " + branchName + " (workspace " + workspace.workspaceId() + ")");
    }
    runGitCommand(
        workspacePath,
        gitTimeout,
        authHeader,
        token,
        "git",
        "checkout",
        "-b",
        branchName,
        sourceSha);

    Instant createdAt = Instant.now();
    log.info(
        "github.write.create_branch success repo={} branch={} workspace={} sourceSha={} localHead={}",
        repository.fullName(),
        branchName,
        workspace.workspaceId(),
        sourceSha,
        sourceSha);
    return new CreateBranchResult(
        repository,
        workspace.workspaceId(),
        branchName,
        "refs/heads/" + branchName,
        sourceSha,
        sourceSha,
        createdAt);
  }

  CommitWorkspaceDiffResult commitWorkspaceDiff(CommitWorkspaceDiffInput input) {
    Objects.requireNonNull(input, "input");
    TempWorkspaceService.Workspace workspace = requireWorkspace(input.workspaceId());
    RepositoryRef repository = repositoryFromWorkspace(workspace);
    String branchName = validateBranchName(input.branchName());
    CommitAuthor author = validateAuthor(input.author());
    String commitMessage = sanitizeCommitMessage(input.commitMessage());

    Path workspacePath = workspace.path();
    String token = tokenManager.currentToken();
    String authHeader = authorizationHeader(token);
    Duration gitTimeout = Duration.ofSeconds(120);

    ensureLocalBranchExists(workspacePath, branchName, authHeader, token);
    runGitCommand(workspacePath, gitTimeout, authHeader, token, "git", "checkout", branchName);

    ProcessResult statusResult =
        runGitCommand(
            workspacePath, gitTimeout, authHeader, token, "git", "status", "--porcelain=1");
    if (!StringUtils.hasText(statusResult.output())) {
      throw new IllegalStateException(
          "Workspace "
              + workspace.workspaceId()
              + " has no staged changes to commit (branch "
              + branchName
              + ")");
    }

    runGitCommand(workspacePath, gitTimeout, authHeader, token, "git", "add", "--all");

    ProcessResult diffResult =
        runGitCommand(workspacePath, gitTimeout, authHeader, token, "git", "diff", "--cached");
    ensureDiffWithinLimits(repository, branchName, diffResult.output());

    ProcessResult nameStatusResult =
        runGitCommand(
            workspacePath,
            gitTimeout,
            authHeader,
            token,
            "git",
            "diff",
            "--cached",
            "--name-status");
    List<CommitFileChange> files = parseCommitFiles(nameStatusResult.output());

    ProcessResult numstatResult =
        runGitCommand(
            workspacePath,
            gitTimeout,
            authHeader,
            token,
            "git",
            "diff",
            "--cached",
            "--numstat");
    CommitStatistics statistics = summarizeNumstat(numstatResult.output());

    int maxFiles =
        Optional.ofNullable(properties.getCommitMaxFiles()).filter(limit -> limit > 0).orElse(200);
    if (files.size() > maxFiles) {
      throw new IllegalStateException(
          "Commit affects %d files, exceeding configured limit %d".formatted(files.size(), maxFiles));
    }

    runGitCommand(
        workspacePath,
        gitTimeout,
        authHeader,
        token,
        "git",
        "-c",
        "user.name=" + author.name(),
        "-c",
        "user.email=" + author.email(),
        "commit",
        "--message",
        commitMessage,
        "--author",
        "%s <%s>".formatted(author.name(), author.email()));

    ProcessResult headResult =
        runGitCommand(workspacePath, Duration.ofSeconds(30), authHeader, token, "git", "rev-parse", "HEAD");
    String commitSha = headResult.output().trim();
    workspaceService.updateWorkspace(workspace.workspaceId(), null, commitSha, null);

    Instant committedAt = Instant.now();
    log.info(
        "github.write.commit_workspace_diff success repo={} branch={} workspace={} commit={} files={} additions={} deletions={}",
        repository.fullName(),
        branchName,
        workspace.workspaceId(),
        commitSha,
        statistics.filesChanged(),
        statistics.additions(),
        statistics.deletions());
    return new CommitWorkspaceDiffResult(
        repository,
        workspace.workspaceId(),
        branchName,
        commitSha,
        statistics.filesChanged(),
        statistics.additions(),
        statistics.deletions(),
        files,
        committedAt);
  }

  PushBranchResult pushBranch(PushBranchInput input) {
    Objects.requireNonNull(input, "input");
    RepositoryRef repository = normalizeRepository(input.repository());
    TempWorkspaceService.Workspace workspace = requireWorkspace(input.workspaceId());
    ensureWorkspaceMatches(workspace, repository);
    String branchName = validateBranchName(input.branchName());
    if (Boolean.TRUE.equals(input.force())) {
      throw new IllegalArgumentException("Force push is disabled for safety reasons");
    }

    Path workspacePath = workspace.path();
    String token = tokenManager.currentToken();
    String authHeader = authorizationHeader(token);
    Duration gitTimeout = Duration.ofSeconds(120);

    ensureLocalBranchExists(workspacePath, branchName, authHeader, token);
    runGitCommand(workspacePath, gitTimeout, authHeader, token, "git", "checkout", branchName);

    ProcessResult statusResult =
        runGitCommand(
            workspacePath, gitTimeout, authHeader, token, "git", "status", "--porcelain=1");
    if (StringUtils.hasText(statusResult.output())) {
      throw new IllegalStateException(
          "Workspace "
              + workspace.workspaceId()
              + " contains uncommitted changes on branch "
              + branchName
              + ". Commit or stash them before push.");
    }

    int commitsAhead = computeAheadCount(workspacePath, branchName, authHeader, token);
    runGitCommand(
        workspacePath,
        gitTimeout,
        authHeader,
        token,
        "git",
        "push",
        "--set-upstream",
        "origin",
        branchName);

    String remoteHead =
        executor.execute(
            github -> {
              try {
                GHRepository repo = github.getRepository(repository.fullName());
                ensurePushAccess(repo, repository);
                GHRef ref = repo.getRef("heads/" + branchName);
                return ref.getObject().getSha();
              } catch (IOException ex) {
                throw new GitHubClientException(
                    "Failed to read head for branch %s in %s"
                        .formatted(branchName, repository.fullName()),
                    ex);
              }
            });

    ProcessResult headResult =
        runGitCommand(workspacePath, Duration.ofSeconds(30), authHeader, token, "git", "rev-parse", "HEAD");
    String localHead = headResult.output().trim();

    Instant pushedAt = Instant.now();
    log.info(
        "github.write.push_branch success repo={} branch={} workspace={} commits={} localHead={} remoteHead={}",
        repository.fullName(),
        branchName,
        workspace.workspaceId(),
        commitsAhead,
        localHead,
        remoteHead);
    return new PushBranchResult(
        repository,
        workspace.workspaceId(),
        branchName,
        localHead,
        remoteHead,
        commitsAhead,
        pushedAt);
  }

  OpenPullRequestResult openPullRequest(OpenPullRequestInput input) {
    Objects.requireNonNull(input, "input");
    RepositoryRef repository = normalizeRepository(input.repository());
    String headBranch = validateBranchName(input.headBranch());
    String baseBranch = validateBranchName(input.baseBranch());
    if (!StringUtils.hasText(input.title())) {
      throw new IllegalArgumentException("title must not be blank");
    }
    if (headBranch.equals(baseBranch)) {
      throw new IllegalArgumentException("headBranch must not be equal to baseBranch");
    }
    String body = input.body() != null ? input.body() : "";
    boolean draft = Boolean.TRUE.equals(input.draft());

    return executor.execute(
        github -> {
          try {
            GHRepository repo = github.getRepository(repository.fullName());
            ensurePushAccess(repo, repository);
            GHRef headRef = repo.getRef("heads/" + headBranch);
            GHRef baseRef = repo.getRef("heads/" + baseBranch);

            enforcePullRequestLimits(repo, baseRef, headRef);

            String headSpec = repository.owner() + ":" + headBranch;
            GHPullRequest pullRequest =
                repo.createPullRequest(input.title().trim(), headSpec, baseBranch, body, draft);

            if (input.reviewers() != null && !input.reviewers().isEmpty()) {
              List<GHUser> reviewers = resolveReviewers(github, input.reviewers());
              if (!reviewers.isEmpty()) {
                pullRequest.requestReviewers(reviewers);
              }
            }
            if (input.teamReviewers() != null && !input.teamReviewers().isEmpty()) {
              List<GHTeam> teams =
                  resolveTeamReviewers(github, repository, repo, input.teamReviewers());
              if (!teams.isEmpty()) {
                pullRequest.requestTeamReviewers(teams);
              }
            }

            Instant createdAt =
                pullRequest.getCreatedAt() != null
                    ? pullRequest.getCreatedAt().toInstant()
                    : Instant.now();
            String headSha =
                pullRequest.getHead() != null
                        && pullRequest.getHead().getSha() != null
                        && !pullRequest.getHead().getSha().isBlank()
                    ? pullRequest.getHead().getSha()
                    : headRef.getObject().getSha();
            String baseSha =
                pullRequest.getBase() != null
                        && pullRequest.getBase().getSha() != null
                        && !pullRequest.getBase().getSha().isBlank()
                    ? pullRequest.getBase().getSha()
                    : baseRef.getObject().getSha();

            log.info(
                "github.write.open_pull_request success repo={} pr={} head={} base={} headSha={} baseSha={} draft={}",
                repository.fullName(),
                pullRequest.getNumber(),
                headBranch,
                baseBranch,
                headSha,
                baseSha,
                draft);
            return new OpenPullRequestResult(
                repository,
                pullRequest.getNumber(),
                pullRequest.getHtmlUrl() != null ? pullRequest.getHtmlUrl().toString() : null,
                headSha,
                baseSha,
                createdAt);
          } catch (HttpException ex) {
            throw new GitHubClientException(
                "Failed to open pull request for %s (HTTP %d)"
                    .formatted(repository.fullName(), ex.getResponseCode()),
                ex);
          } catch (IOException ex) {
            throw new GitHubClientException(
                "Failed to open pull request for " + repository.fullName(), ex);
          }
        });
  }

  ApprovePullRequestResult approvePullRequest(ApprovePullRequestInput input) {
    Objects.requireNonNull(input, "input");
    RepositoryRef repository = normalizeRepository(input.repository());
    int number =
        Optional.ofNullable(input.number())
            .filter(value -> value > 0)
            .orElseThrow(() -> new IllegalArgumentException("pullRequest number must be positive"));
    String body = input.body();

    return executor.execute(
        github -> {
          try {
            GHRepository repo = github.getRepository(repository.fullName());
            ensurePushAccess(repo, repository);
            GHPullRequest pullRequest = repo.getPullRequest(number);
            GHPullRequestReviewBuilder builder = pullRequest.createReview().event(GHPullRequestReviewEvent.APPROVE);
            if (StringUtils.hasText(body)) {
              builder.body(body.trim());
            }
            GHPullRequestReview review = builder.create();
            Instant submittedAt =
                review.getSubmittedAt() != null
                    ? review.getSubmittedAt().toInstant()
                    : Instant.now();
            log.info(
                "github.write.approve_pull_request success repo={} pr={} reviewId={} state={}",
                repository.fullName(),
                number,
                review.getId(),
                review.getState());
            return new ApprovePullRequestResult(
                repository,
                number,
                review.getId(),
                review.getState() != null ? review.getState().name() : GHPullRequestReviewState.APPROVED.name(),
                submittedAt);
          } catch (IOException ex) {
            throw new GitHubClientException(
                "Failed to approve pull request %s#%d"
                    .formatted(repository.fullName(), number),
                ex);
          }
        });
  }

  MergePullRequestResult mergePullRequest(MergePullRequestInput input) {
    Objects.requireNonNull(input, "input");
    RepositoryRef repository = normalizeRepository(input.repository());
    int number =
        Optional.ofNullable(input.number())
            .filter(value -> value > 0)
            .orElseThrow(() -> new IllegalArgumentException("pullRequest number must be positive"));
    MergeMethod method = Optional.ofNullable(input.method()).orElse(MergeMethod.MERGE);
    String commitTitle = input.commitTitle();
    String commitMessage = input.commitMessage();

    return executor.execute(
        github -> {
          try {
            GHRepository repo = github.getRepository(repository.fullName());
            ensurePushAccess(repo, repository);
            GHPullRequest pullRequest = repo.getPullRequest(number);
            pullRequest.refresh();
            Boolean mergeable = pullRequest.getMergeable();
            String mergeableState = pullRequest.getMergeableState();
            if (mergeable != null && !mergeable) {
              throw new IllegalStateException(
                  "Pull request %s#%d is not mergeable (state=%s)"
                      .formatted(repository.fullName(), number, mergeableState));
            }

            GHPullRequest.MergeMethod ghMergeMethod = mapMergeMethod(method);
            String title =
                StringUtils.hasText(commitTitle)
                    ? commitTitle.trim()
                    : Optional.ofNullable(pullRequest.getTitle()).orElse("Merge pull request #" + number);
            String message = commitMessage != null ? commitMessage : "";
            pullRequest.merge(title, message, ghMergeMethod);

            pullRequest.refresh();
            boolean merged = pullRequest.isMerged();
            String mergeSha =
                merged && pullRequest.getMergeCommitSha() != null
                    ? pullRequest.getMergeCommitSha()
                    : null;
            Instant mergedAt =
                pullRequest.getMergedAt() != null
                    ? pullRequest.getMergedAt().toInstant()
                    : Instant.now();
            String state =
                pullRequest.getMergeableState() != null ? pullRequest.getMergeableState() : "unknown";

            log.info(
                "github.write.merge_pull_request success repo={} pr={} method={} merged={} mergeSha={} state={}",
                repository.fullName(),
                number,
                method,
                merged,
                mergeSha,
                state);

            return new MergePullRequestResult(
                repository, number, merged, mergeSha, state, mergedAt);
          } catch (IOException ex) {
            throw new GitHubClientException(
                "Failed to merge pull request %s#%d"
                    .formatted(repository.fullName(), number),
                ex);
          }
        });
  }

  private TempWorkspaceService.Workspace requireWorkspace(String workspaceId) {
    if (!StringUtils.hasText(workspaceId)) {
      throw new IllegalArgumentException("workspaceId must not be blank");
    }
    return workspaceService
        .findWorkspace(workspaceId.trim())
        .orElseThrow(() -> new IllegalArgumentException("Unknown workspaceId: " + workspaceId));
  }

  private RepositoryRef repositoryFromWorkspace(TempWorkspaceService.Workspace workspace) {
    String fullName = workspace.repositoryFullName();
    if (!StringUtils.hasText(fullName) || !fullName.contains("/")) {
      throw new IllegalStateException(
          "Workspace %s is missing repository metadata".formatted(workspace.workspaceId()));
    }
    String[] parts = fullName.split("/", 2);
    String ref = StringUtils.hasText(workspace.ref()) ? workspace.ref() : "heads/main";
    return new RepositoryRef(parts[0], parts[1], ref);
  }

  private void ensureWorkspaceMatches(
      TempWorkspaceService.Workspace workspace, RepositoryRef repository) {
    String expected = repository.owner() + "/" + repository.name();
    if (!expected.equalsIgnoreCase(workspace.repositoryFullName())) {
      throw new IllegalArgumentException(
          "Workspace "
              + workspace.workspaceId()
              + " belongs to "
              + workspace.repositoryFullName()
              + " but request targets "
              + expected);
    }
  }

  private String validateBranchName(String branchName) {
    if (!StringUtils.hasText(branchName)) {
      throw new IllegalArgumentException("branchName must not be blank");
    }
    String trimmed = branchName.trim();
    if (trimmed.length() > MAX_BRANCH_NAME_LENGTH) {
      throw new IllegalArgumentException("branchName exceeds %d characters".formatted(MAX_BRANCH_NAME_LENGTH));
    }
    if (trimmed.startsWith("/") || trimmed.endsWith("/") || trimmed.endsWith(".")) {
      throw new IllegalArgumentException("branchName must not start or end with '/' or '.'");
    }
    if (trimmed.contains("..")) {
      throw new IllegalArgumentException("branchName must not contain '..'");
    }
    if (INVALID_BRANCH_PATTERN.matcher(trimmed).find()) {
      throw new IllegalArgumentException("branchName contains forbidden characters");
    }
    String[] segments = trimmed.split("/");
    for (String segment : segments) {
      if (!StringUtils.hasText(segment)) {
        throw new IllegalArgumentException("branchName must not contain empty path components");
      }
      if (INVALID_BRANCH_COMPONENT.matcher(segment).find()) {
        throw new IllegalArgumentException("branchName component '" + segment + "' is invalid");
      }
    }
    return trimmed;
  }

  private String resolveSourceSha(
      TempWorkspaceService.Workspace workspace,
      String requested,
      Path workspacePath,
      String authHeader,
      String token) {
    if (StringUtils.hasText(requested)) {
      String trimmed = requested.trim();
      if (!trimmed.matches("(?i)[0-9a-f]{40}")) {
        throw new IllegalArgumentException("sourceSha must be a 40-character hexadecimal string");
      }
      return trimmed.toLowerCase(Locale.ROOT);
    }
    if (StringUtils.hasText(workspace.commitSha())) {
      return workspace.commitSha();
    }
    ProcessResult headResult =
        runGitCommand(workspacePath, Duration.ofSeconds(30), authHeader, token, "git", "rev-parse", "HEAD");
    if (!StringUtils.hasText(headResult.output())) {
      throw new IllegalStateException(
          "Unable to resolve HEAD commit for workspace " + workspace.workspaceId());
    }
    return headResult.output().trim();
  }

  private void ensureGitRepositoryInitialized(
      RepositoryRef repository,
      TempWorkspaceService.Workspace workspace,
      Path workspacePath,
      String sourceSha,
      Duration gitTimeout,
      String authHeader,
      String token) {
    Path gitDir = workspacePath.resolve(".git");
    if (Files.isDirectory(gitDir)) {
      return;
    }
    if (!StringUtils.hasText(sourceSha)) {
      throw new IllegalStateException(
          "Workspace "
              + workspace.workspaceId()
              + " is missing git metadata and no commit SHA is available to reconstruct it."
              + " Re-fetch the repository with clone strategy.");
    }
    log.info(
        "Workspace {} missing .git directory, initializing git repository for {}",
        workspace.workspaceId(),
        repository.fullName());
    runGitCommand(workspacePath, gitTimeout, authHeader, token, "git", "init");
    ensureMetadataExcluded(workspacePath);
    String remoteUrl = buildCloneUrl(repository);
    runGitCommand(
        workspacePath,
        gitTimeout,
        authHeader,
        token,
        "git",
        "remote",
        "add",
        "origin",
        remoteUrl);
    runGitCommand(workspacePath, gitTimeout, authHeader, token, "git", "fetch", "origin", sourceSha);
    runGitCommand(workspacePath, gitTimeout, authHeader, token, "git", "checkout", "-f", sourceSha);
  }

  private void ensureMetadataExcluded(Path workspacePath) {
    Path gitDir = workspacePath.resolve(".git");
    if (!Files.isDirectory(gitDir)) {
      return;
    }
    Path excludeFile = gitDir.resolve("info").resolve("exclude");
    try {
      Path excludeDir = excludeFile.getParent();
      if (excludeDir != null) {
        Files.createDirectories(excludeDir);
      }
      if (Files.exists(excludeFile)) {
        List<String> existing = Files.readAllLines(excludeFile, StandardCharsets.UTF_8);
        boolean alreadyPresent =
            existing.stream().map(String::trim).anyMatch(line -> WORKSPACE_METADATA_FILE.equals(line));
        if (alreadyPresent) {
          return;
        }
      }
      boolean needsNewline = Files.exists(excludeFile) && Files.size(excludeFile) > 0;
      try (BufferedWriter writer =
          Files.newBufferedWriter(
              excludeFile,
              StandardCharsets.UTF_8,
              StandardOpenOption.CREATE,
              StandardOpenOption.APPEND)) {
        if (needsNewline) {
          writer.newLine();
        }
        writer.write(WORKSPACE_METADATA_FILE);
        writer.newLine();
      }
    } catch (IOException ex) {
      log.debug(
          "Failed to add {} to git exclude for workspace {}: {}",
          WORKSPACE_METADATA_FILE,
          workspacePath,
          ex.getMessage());
    }
  }

  private void ensurePushAccess(GHRepository repository, RepositoryRef ref) throws IOException {
    if (!repository.hasPushAccess()) {
      throw new IllegalStateException(
          "Configured token does not have push access to " + ref.owner() + "/" + ref.name());
    }
  }

  private void ensureRemoteBranchAbsent(
      GHRepository repository, String branchName, RepositoryRef ref) throws IOException {
    try {
      repository.getRef("heads/" + branchName);
      throw new IllegalStateException(
          "Branch %s already exists in %s".formatted(branchName, ref.owner() + "/" + ref.name()));
    } catch (GHFileNotFoundException ex) {
      return;
    } catch (HttpException ex) {
      if (ex.getResponseCode() == 404) {
        return;
      }
      throw ex;
    }
  }

  private boolean branchExistsLocally(Path workspacePath, String branchName) {
    ProcessBuilder builder =
        new ProcessBuilder("git", "show-ref", "--verify", "--quiet", "refs/heads/" + branchName);
    builder.directory(workspacePath.toFile());
    builder.environment().put("GIT_TERMINAL_PROMPT", "0");
    builder.environment().putIfAbsent("LC_ALL", "C");
    ProcessResult result = runProcess(builder, Duration.ofSeconds(10));
    return result.exitCode() == 0;
  }

  private void ensureLocalBranchExists(
      Path workspacePath, String branchName, String authHeader, String token) {
    try {
      runGitCommand(
          workspacePath,
          Duration.ofSeconds(30),
          authHeader,
          token,
          "git",
          "rev-parse",
          "--verify",
          "refs/heads/" + branchName);
    } catch (GitHubClientException ex) {
      throw new IllegalStateException(
          "Branch "
              + branchName
              + " does not exist in workspace. Create it with github.create_branch first.",
          ex);
    }
  }

  private CommitAuthor validateAuthor(CommitAuthor author) {
    if (author == null) {
      throw new IllegalArgumentException("author block is required");
    }
    if (!StringUtils.hasText(author.name())) {
      throw new IllegalArgumentException("author.name must not be blank");
    }
    if (!StringUtils.hasText(author.email())) {
      throw new IllegalArgumentException("author.email must not be blank");
    }
    String name = author.name().trim();
    String email = author.email().trim();
    if (!email.contains("@")) {
      throw new IllegalArgumentException("author.email must contain '@'");
    }
    return new CommitAuthor(name, email);
  }

  private String sanitizeCommitMessage(String commitMessage) {
    if (!StringUtils.hasText(commitMessage)) {
      throw new IllegalArgumentException("commitMessage must not be blank");
    }
    String trimmed = commitMessage.strip();
    if (trimmed.length() > 2000) {
      throw new IllegalArgumentException("commitMessage exceeds 2000 characters");
    }
    return trimmed;
  }

  private void ensureDiffWithinLimits(
      RepositoryRef repository, String branchName, String diffOutput) {
    if (!StringUtils.hasText(diffOutput)) {
      throw new IllegalStateException(
          "No staged changes detected for repository "
              + repository.owner()
              + "/"
              + repository.name()
              + " on branch "
              + branchName);
    }
    long maxBytes =
        Optional.ofNullable(properties.getCommitDiffMaxBytes())
            .filter(limit -> limit > 0)
            .orElse(MAX_DIFF_BYTES);
    byte[] bytes = diffOutput.getBytes(StandardCharsets.UTF_8);
    if (bytes.length > maxBytes) {
      throw new IllegalArgumentException(
          "Staged diff size %d bytes exceeds limit %d".formatted(bytes.length, maxBytes));
    }
  }

  private List<CommitFileChange> parseCommitFiles(String output) {
    if (!StringUtils.hasText(output)) {
      return List.of();
    }
    List<CommitFileChange> changes = new ArrayList<>();
    String[] lines = output.split("\\R");
    for (String rawLine : lines) {
      if (!StringUtils.hasText(rawLine)) {
        continue;
      }
      String line = rawLine.trim();
      String[] columns = line.split("\\t");
      if (columns.length < 2) {
        continue;
      }
      String status = columns[0].trim();
      String path = columns[columns.length - 1].trim();
      String previous = null;
      if ((status.startsWith("R") || status.startsWith("C")) && columns.length >= 3) {
        previous = columns[1].trim();
        path = columns[2].trim();
      }
      changes.add(new CommitFileChange(status, path, previous));
    }
    return List.copyOf(changes);
  }

  private CommitStatistics summarizeNumstat(String output) {
    if (!StringUtils.hasText(output)) {
      return new CommitStatistics(0, 0, 0);
    }
    int files = 0;
    int additions = 0;
    int deletions = 0;
    String[] lines = output.split("\\R");
    for (String line : lines) {
      if (!StringUtils.hasText(line)) {
        continue;
      }
      String[] columns = line.trim().split("\\t");
      if (columns.length < 3) {
        continue;
      }
      files++;
      additions += parseStatValue(columns[0]);
      deletions += parseStatValue(columns[1]);
    }
    return new CommitStatistics(files, additions, deletions);
  }

  private int parseStatValue(String raw) {
    if (!StringUtils.hasText(raw) || raw.equals("-")) {
      return 0;
    }
    try {
      return Integer.parseInt(raw.trim());
    } catch (NumberFormatException ex) {
      return 0;
    }
  }

  private List<GHUser> resolveReviewers(
      org.kohsuke.github.GitHub github, List<String> reviewerLogins) throws IOException {
    if (reviewerLogins == null || reviewerLogins.isEmpty()) {
      return List.of();
    }
    List<GHUser> reviewers = new ArrayList<>();
    for (String login : reviewerLogins) {
      if (!StringUtils.hasText(login)) {
        continue;
      }
      GHUser user = github.getUser(login.trim());
      if (user != null) {
        reviewers.add(user);
      }
    }
    return List.copyOf(reviewers);
  }

  private List<GHTeam> resolveTeamReviewers(
      org.kohsuke.github.GitHub github,
      RepositoryRef repository,
      GHRepository repo,
      List<String> teamSlugs)
      throws IOException {
    if (teamSlugs == null || teamSlugs.isEmpty()) {
      return List.of();
    }
    GHOrganization organization =
        github.getOrganization(repository.owner());
    if (organization == null) {
      log.warn(
          "Repository {} is not owned by an organization; team reviewers are ignored",
          repository.fullName());
      return List.of();
    }
    List<GHTeam> teams = new ArrayList<>();
    for (String slug : teamSlugs) {
      if (!StringUtils.hasText(slug)) {
        continue;
      }
      GHTeam team = organization.getTeamBySlug(slug.trim());
      if (team != null) {
        teams.add(team);
      } else {
        log.warn(
            "Unable to resolve team '{}' for organization {}. Team reviewer will be skipped.",
            slug,
            organization.getLogin());
      }
    }
    return List.copyOf(teams);
  }

  private int computeAheadCount(
      Path workspacePath, String branchName, String authHeader, String token) {
    try {
      ProcessResult result =
          runGitCommand(
              workspacePath,
              Duration.ofSeconds(30),
              authHeader,
              token,
              "git",
              "rev-list",
              "--count",
              "origin/" + branchName + ".." + branchName);
      String output = result.output().trim();
      if (StringUtils.hasText(output)) {
        return Integer.parseInt(output);
      }
    } catch (GitHubClientException | NumberFormatException ex) {
      log.debug("Failed to compute ahead count via origin comparison: {}", ex.getMessage());
    }
    try {
      ProcessResult fallback =
          runGitCommand(
              workspacePath,
              Duration.ofSeconds(30),
              authHeader,
              token,
              "git",
              "rev-list",
              "--count",
              branchName);
      String output = fallback.output().trim();
      if (StringUtils.hasText(output)) {
        return Integer.parseInt(output);
      }
    } catch (GitHubClientException | NumberFormatException ex) {
      log.debug("Failed to compute local commit count for {}: {}", branchName, ex.getMessage());
    }
    return 0;
  }

  private void enforcePullRequestLimits(
      GHRepository repository, GHRef baseRef, GHRef headRef) throws IOException {
    Integer maxFiles =
        Optional.ofNullable(properties.getPullRequestMaxFiles()).filter(limit -> limit > 0).orElse(null);
    Integer maxCommits =
        Optional.ofNullable(properties.getPullRequestMaxCommits()).filter(limit -> limit > 0).orElse(null);
    if (maxFiles == null && maxCommits == null) {
      return;
    }
    GHCompare compare =
        repository.getCompare(baseRef.getObject().getSha(), headRef.getObject().getSha());
    if (maxFiles != null) {
      GHCommit.File[] files = compare.getFiles();
      int count = files != null ? files.length : 0;
      if (count > maxFiles) {
        throw new IllegalArgumentException(
            "Pull request diff contains %d files, exceeds limit %d".formatted(count, maxFiles));
      }
    }
    if (maxCommits != null && compare.getTotalCommits() > maxCommits) {
      throw new IllegalArgumentException(
          "Pull request contains %d commits, exceeds limit %d"
              .formatted(compare.getTotalCommits(), maxCommits));
    }
  }

  private GHPullRequest.MergeMethod mapMergeMethod(MergeMethod method) {
    return switch (method) {
      case MERGE -> GHPullRequest.MergeMethod.MERGE;
      case SQUASH -> GHPullRequest.MergeMethod.SQUASH;
      case REBASE -> GHPullRequest.MergeMethod.REBASE;
    };
  }

  private TreeData loadTree(RepositoryRef repository, int requestedDepth) {
    int depth = Math.max(1, Math.min(10, requestedDepth));
    String cacheKey = treeCacheKey(repository, depth);
    CachedTree cached = treeCache.get(cacheKey);
    if (cached != null && !cached.isExpired()) {
      return cached.payload();
    }

    TreeData loaded = executor.execute(github -> fetchTree(github, repository, depth));
    treeCache.put(cacheKey, new CachedTree(loaded, expiry(properties.getTreeCacheTtl())));
    return loaded;
  }

  private FileData loadFile(RepositoryRef repository, String path) {
    String cacheKey = fileCacheKey(repository, path);
    CachedFile cached = fileCache.get(cacheKey);
    if (cached != null && !cached.isExpired()) {
      return cached.payload();
    }
    FileData loaded = executor.execute(github -> fetchFile(github, repository, path));
    fileCache.put(cacheKey, new CachedFile(loaded, expiry(properties.getFileCacheTtl())));
    return loaded;
  }

  private PullRequestListData fetchPullRequests(
      org.kohsuke.github.GitHub github,
      RepositoryRef repository,
      ListPullRequestsInput input,
      int limit) {
    try {
      GHRepository repo = github.getRepository(repository.fullName());
      GHPullRequestQueryBuilder builder = repo.queryPullRequests();

      GHIssueState state = parseIssueState(input.state());
      if (state != null) {
        builder.state(state);
      }
      if (StringUtils.hasText(input.base())) {
        builder.base(input.base().trim());
      }
      if (StringUtils.hasText(input.head())) {
        builder.head(input.head().trim());
      }
      Optional.ofNullable(parseSort(input.sort())).ifPresent(builder::sort);
      Optional.ofNullable(parseDirection(input.direction())).ifPresent(builder::direction);

      PagedIterable<GHPullRequest> iterable = builder.list().withPageSize(Math.min(limit, MAX_PR_PAGE_LIMIT));

      List<PullRequestSummary> summaries = new ArrayList<>();
      boolean truncated = false;
      for (GHPullRequest pr : iterable) {
        summaries.add(toPullRequestSummarySafe(repository, pr));
        if (summaries.size() >= limit) {
          truncated = true;
          break;
        }
      }
      return new PullRequestListData(List.copyOf(summaries), truncated);
    } catch (IOException ex) {
      throw new GitHubClientException(
          "Failed to list pull requests for %s".formatted(repository.fullName()), ex);
    }
  }

  private PullRequestDetailsData fetchPullRequestDetails(
      org.kohsuke.github.GitHub github, RepositoryRef repository, int number) {
    try {
      GHRepository repo = github.getRepository(repository.fullName());
      GHPullRequest pr = repo.getPullRequest(number);
      List<UserInfo> assignees = toUserList(pr.getAssignees());
      List<UserInfo> requestedReviewers = toUserList(pr.getRequestedReviewers());
      List<TeamInfo> requestedTeams = toTeamList(pr.getRequestedTeams());
      return new PullRequestDetailsData(
          toPullRequestDetails(pr, assignees, requestedReviewers, requestedTeams));
    } catch (IOException ex) {
      throw new GitHubClientException(
          "Failed to load pull request %s#%d".formatted(repository.fullName(), number), ex);
    }
  }

  private PullRequestDiffData fetchPullRequestDiff(
      org.kohsuke.github.GitHub github,
      RepositoryRef repository,
      int number,
      long maxBytes) {
    try {
      GHRepository repo = github.getRepository(repository.fullName());
      GHPullRequest pr = repo.getPullRequest(number);
      StringBuilder diffBuilder = new StringBuilder();
      for (GHPullRequestFileDetail file : pr.listFiles()) {
        String filename = safeTrim(file.getFilename());
        if (StringUtils.hasText(filename)) {
          diffBuilder
              .append("diff --git a/")
              .append(filename)
              .append(" b/")
              .append(filename)
              .append("\n");
        }
        String patch = file.getPatch();
        if (patch != null) {
          diffBuilder.append(patch);
          if (!patch.endsWith("\n")) {
            diffBuilder.append('\n');
          }
        }
      }
      String diffPayload = diffBuilder.toString();
      TruncatedValue truncated = truncateUtf8(diffPayload, maxBytes);
      String ref = pr.getHead() != null ? pr.getHead().getSha() : null;
      return new PullRequestDiffData(ref, truncated.value(), truncated.truncated());
    } catch (IOException ex) {
      throw new GitHubClientException(
          "Failed to fetch diff for pull request %s#%d".formatted(repository.fullName(), number), ex);
    }
  }

  private PullRequestCommentsData fetchPullRequestComments(
      org.kohsuke.github.GitHub github,
      RepositoryRef repository,
      int number,
      int issueLimit,
      int reviewLimit) {
    try {
      GHRepository repo = github.getRepository(repository.fullName());
      GHPullRequest pr = repo.getPullRequest(number);

      List<PullRequestIssueComment> issueComments = new ArrayList<>();
      boolean truncatedIssue = false;
      if (issueLimit != 0) {
        PagedIterable<GHIssueComment> issueIterable = pr.listComments();
        for (GHIssueComment comment : issueIterable) {
          issueComments.add(toIssueComment(comment));
          if (issueComments.size() >= issueLimit) {
            truncatedIssue = true;
            break;
          }
        }
      }

      List<PullRequestReviewComment> reviewComments = new ArrayList<>();
      boolean truncatedReview = false;
      if (reviewLimit != 0) {
        PagedIterable<GHPullRequestReviewComment> reviewIterable = pr.listReviewComments();
        for (GHPullRequestReviewComment comment : reviewIterable) {
          reviewComments.add(toReviewComment(comment));
          if (reviewComments.size() >= reviewLimit) {
            truncatedReview = true;
            break;
          }
        }
      }

      return new PullRequestCommentsData(
          List.copyOf(issueComments),
          List.copyOf(reviewComments),
          truncatedIssue,
          truncatedReview);
    } catch (IOException ex) {
      throw new GitHubClientException(
          "Failed to list comments for pull request %s#%d".formatted(repository.fullName(), number), ex);
    }
  }

  private PullRequestChecksData fetchPullRequestChecks(
      org.kohsuke.github.GitHub github,
      RepositoryRef repository,
      int number,
      int checkRunLimit,
      int statusLimit) {
    try {
      GHRepository repo = github.getRepository(repository.fullName());
      GHPullRequest pr = repo.getPullRequest(number);
      String headSha = pr.getHead() != null ? pr.getHead().getSha() : null;

      List<CheckRunInfo> checkRuns = new ArrayList<>();
      boolean truncatedRuns = false;
      if (headSha != null && checkRunLimit != 0) {
        PagedIterable<GHCheckRun> runsIterable = repo.getCheckRuns(headSha).withPageSize(Math.min(checkRunLimit, MAX_CHECK_LIMIT));
        for (GHCheckRun run : runsIterable) {
          CheckRunInfo info = toCheckRun(run);
          if (info != null) {
            checkRuns.add(info);
          }
          if (checkRuns.size() >= checkRunLimit) {
            truncatedRuns = true;
            break;
          }
        }
      }

      List<CommitStatusInfo> statuses = new ArrayList<>();
      boolean truncatedStatuses = false;
      if (headSha != null) {
        GHCommit commit = repo.getCommit(headSha);
        if (statusLimit != 0) {
          PagedIterable<GHCommitStatus> statusIterable = commit.listStatuses().withPageSize(Math.min(statusLimit, MAX_CHECK_LIMIT));
          for (GHCommitStatus status : statusIterable) {
            CommitStatusInfo info = toCommitStatus(status);
            if (info != null) {
              statuses.add(info);
            }
            if (statuses.size() >= statusLimit) {
              truncatedStatuses = true;
              break;
            }
          }
        }
      }

      String overallStatus = statuses.isEmpty() ? null : statuses.get(0).state();

      return new PullRequestChecksData(
          number,
          headSha,
          overallStatus,
          List.copyOf(checkRuns),
          List.copyOf(statuses),
          truncatedRuns,
          truncatedStatuses);
    } catch (IOException ex) {
      throw new GitHubClientException(
          "Failed to list checks for pull request %s#%d".formatted(repository.fullName(), number), ex);
    }
  }

  CreatePullRequestCommentResult createPullRequestComment(CreatePullRequestCommentInput input) {
    Objects.requireNonNull(input, "input must not be null");
    RepositoryRef repository = normalizeRepository(input.repository());
    int number = requirePositive(input.number(), "pullRequestNumber");
    String body = safeTrim(input.body());
    if (!StringUtils.hasText(body)) {
      throw new IllegalArgumentException("Comment body must not be blank");
    }

    ReviewCommentLocation reviewLocation = input.reviewLocation();

    return executor.execute(
        github -> {
          try {
            GHRepository repo = github.getRepository(repository.fullName());
            GHPullRequest pr = repo.getPullRequest(number);
            if (reviewLocation == null) {
              GHIssueComment existing = findMatchingIssueComment(pr, body);
              if (existing != null) {
                return toIssueCommentResult(repository, number, existing, false);
              }
              GHIssueComment created = pr.comment(body);
              return toIssueCommentResult(repository, number, created, true);
            }

            validateReviewCommentLocation(reviewLocation);
            GHPullRequestReviewComment existing = findMatchingReviewComment(pr, reviewLocation, body);
            if (existing != null) {
              return toReviewCommentResult(repository, number, existing, false);
            }

            String trimmedPath = reviewLocation.path().trim();
            Integer line = reviewLocation.line();
            Integer startLine = reviewLocation.startLine();
            Integer endLine = reviewLocation.endLine();
            boolean hasRange = startLine != null && endLine != null;
            boolean multiLineRange = hasRange && !Objects.equals(startLine, endLine);
            boolean singleLineRange = hasRange && Objects.equals(startLine, endLine);
            Integer effectiveLine = line != null ? line : (singleLineRange ? startLine : null);
            Integer diffPosition = reviewLocation.position();
            if (diffPosition != null && effectiveLine == null && !multiLineRange) {
              String commitSha = StringUtils.hasText(reviewLocation.commitSha())
                  ? reviewLocation.commitSha().trim()
                  : null;
              GHPullRequestReviewComment created =
                  pr.createReviewComment(body, commitSha, trimmedPath, diffPosition);
              return toReviewCommentResult(repository, number, created, true);
            }

            GHPullRequestReviewCommentBuilder builder = pr.createReviewComment().body(body).path(trimmedPath);
            if (StringUtils.hasText(reviewLocation.commitSha())) {
              builder.commitId(reviewLocation.commitSha().trim());
            }
            if (multiLineRange) {
              builder.lines(startLine, endLine);
            } else if (effectiveLine != null) {
              builder.line(effectiveLine);
            } else {
              throw new IllegalStateException("Unsupported review comment location configuration");
          }
            GHPullRequestReviewComment created = builder.create();
            return toReviewCommentResult(repository, number, created, true);
          } catch (IOException ex) {
            throw wrapGitHubException(
                "Failed to create comment for pull request %s#%d".formatted(repository.fullName(), number), ex);
          }
        });
  }

  CreatePullRequestReviewResult createPullRequestReview(CreatePullRequestReviewInput input) {
    Objects.requireNonNull(input, "input must not be null");
    RepositoryRef repository = normalizeRepository(input.repository());
    int number = requirePositive(input.number(), "pullRequestNumber");
    GHPullRequestReviewEvent event = parseReviewEvent(input.event());
    String body = safeTrim(input.body());
    if (event == GHPullRequestReviewEvent.COMMENT || event == GHPullRequestReviewEvent.REQUEST_CHANGES) {
      if (!StringUtils.hasText(body)) {
        throw new IllegalArgumentException("Review body is required for COMMENT or REQUEST_CHANGES");
      }
    }

    List<ReviewCommentDraft> drafts =
        input.comments() == null ? List.of() : List.copyOf(input.comments());

    return executor.execute(
        github -> {
          String commitId = safeTrim(input.commitId());
          try {
            GHRepository repo = github.getRepository(repository.fullName());
            GHPullRequest pr = repo.getPullRequest(number);

            GHPullRequestReviewState targetState = toReviewState(event);

            if (drafts.isEmpty()) {
              GHPullRequestReview duplicate = findMatchingReview(pr, targetState, body);
              if (duplicate != null) {
                return new CreatePullRequestReviewResult(
                    repository, number, toReviewInfo(duplicate), false);
              }
            }

            GHPullRequestReviewBuilder builder = pr.createReview();
            if (!StringUtils.hasText(commitId)) {
              commitId = safeTrim(pr.getHead() != null ? pr.getHead().getSha() : null);
            }
            if (!StringUtils.hasText(commitId)) {
              throw new IllegalStateException(
                  "Unable to resolve commit id for pull request review creation");
            }
            builder.commitId(commitId);
            if (StringUtils.hasText(body)) {
              builder.body(body);
            }
            if (event != null && event != GHPullRequestReviewEvent.PENDING) {
              builder.event(event);
            }
            for (ReviewCommentDraft draft : drafts) {
              appendDraft(builder, draft);
            }
            GHPullRequestReview review = builder.create();
            return new CreatePullRequestReviewResult(
                repository, number, toReviewInfo(review), true);
          } catch (IOException ex) {
            throw new GitHubClientException(
                "Failed to create review for pull request %s#%d".formatted(repository.fullName(), number), ex);
          }
        });
  }

  SubmitPullRequestReviewResult submitPullRequestReview(SubmitPullRequestReviewInput input) {
    Objects.requireNonNull(input, "input must not be null");
    RepositoryRef repository = normalizeRepository(input.repository());
    int number = requirePositive(input.number(), "pullRequestNumber");
    long reviewId = requirePositiveLong(input.reviewId(), "reviewId");
    GHPullRequestReviewEvent event = parseReviewEvent(input.event());
    if (event == null || event == GHPullRequestReviewEvent.PENDING) {
      throw new IllegalArgumentException("Review submission requires event APPROVE, REQUEST_CHANGES or COMMENT");
    }
    String body = safeTrim(input.body());
    if ((event == GHPullRequestReviewEvent.COMMENT || event == GHPullRequestReviewEvent.REQUEST_CHANGES)
        && !StringUtils.hasText(body)) {
      throw new IllegalArgumentException("Review body is required for COMMENT or REQUEST_CHANGES submission");
    }

    final String payloadBody = body != null ? body : "";

    return executor.execute(
        github -> {
          try {
            GHRepository repo = github.getRepository(repository.fullName());
            GHPullRequest pr = repo.getPullRequest(number);
            GHPullRequestReview review = findReviewById(pr, reviewId);
            if (review == null) {
              throw new IllegalArgumentException(
                  "Review %d not found for pull request %s#%d".formatted(reviewId, repository.fullName(), number));
            }
            review.submit(payloadBody, event);
            GHPullRequestReview refreshed = findReviewById(pr, reviewId);
            if (refreshed == null) {
              refreshed = review;
            }
            return new SubmitPullRequestReviewResult(
                repository, number, toReviewInfo(refreshed));
          } catch (IOException ex) {
            throw new GitHubClientException(
                "Failed to submit review %d for pull request %s#%d"
                    .formatted(reviewId, repository.fullName(), number),
                ex);
          }
        });
  }

  private PullRequestSummary toPullRequestSummarySafe(RepositoryRef repository, GHPullRequest pr) {
    try {
      return toPullRequestSummary(pr);
    } catch (IOException ex) {
      throw new GitHubClientException(
          "Failed to read pull request %s#%d".formatted(repository.fullName(), pr.getNumber()), ex);
    }
  }

  private PullRequestSummary toPullRequestSummary(GHPullRequest pr) throws IOException {
    boolean draft = pr.isDraft();
    boolean merged = pr.isMerged();
    return new PullRequestSummary(
        pr.getNumber(),
        safeTrim(pr.getTitle()),
        pr.getState() != null ? pr.getState().name().toLowerCase(Locale.ROOT) : null,
        draft,
        merged,
        toUser(pr.getUser()),
        pr.getBase() != null ? pr.getBase().getRef() : null,
        pr.getHead() != null ? pr.getHead().getRef() : null,
        pr.getHead() != null ? pr.getHead().getSha() : null,
        toInstant(pr.getCreatedAt()),
        toInstant(pr.getUpdatedAt()),
        toInstant(pr.getClosedAt()),
        toInstant(pr.getMergedAt()),
        safeUrl(pr.getHtmlUrl()));
  }

  private PullRequestDetails toPullRequestDetails(
      GHPullRequest pr,
      List<UserInfo> assignees,
      List<UserInfo> requestedReviewers,
      List<TeamInfo> requestedTeams)
      throws IOException {
    PullRequestSummary summary = toPullRequestSummary(pr);

    List<LabelInfo> labels = toLabelInfos(pr.getLabels());
    MilestoneInfo milestone = toMilestone(pr.getMilestone());
    MergeInfo merge =
        new MergeInfo(
            pr.getMergeable(),
            safeLower(pr.getMergeableState()),
            summary.draft());

    Boolean maintainerCanModify = null;
    try {
      maintainerCanModify = pr.canMaintainerModify();
    } catch (IOException ex) {
      log.debug("Unable to resolve maintainer permissions for PR {}: {}", pr.getNumber(), ex.getMessage());
    }

    String mergeCommitSha = null;
    try {
      mergeCommitSha = pr.getMergeCommitSha();
    } catch (IOException ex) {
      log.debug("Unable to resolve merge commit SHA for PR {}: {}", pr.getNumber(), ex.getMessage());
    }

    return new PullRequestDetails(
        summary,
        safeTrim(pr.getBody()),
        labels,
        assignees,
        requestedReviewers,
        requestedTeams,
        milestone,
        merge,
        maintainerCanModify,
        mergeCommitSha);
  }

  private List<LabelInfo> toLabelInfos(java.util.Collection<GHLabel> labels) {
    if (labels == null || labels.isEmpty()) {
      return List.of();
    }
    List<LabelInfo> result = new ArrayList<>(labels.size());
    for (GHLabel label : labels) {
      if (label == null) {
        continue;
      }
      result.add(
          new LabelInfo(
              safeTrim(label.getName()),
              safeTrim(label.getColor()),
              safeTrim(label.getDescription())));
    }
    return List.copyOf(result);
  }

  private List<UserInfo> toUserList(java.util.Collection<? extends GHUser> users) {
    if (users == null || users.isEmpty()) {
      return List.of();
    }
    List<UserInfo> result = new ArrayList<>(users.size());
    for (GHUser user : users) {
      result.add(toUser(user));
    }
    return List.copyOf(result);
  }

  private List<TeamInfo> toTeamList(List<GHTeam> teams) {
    if (teams == null || teams.isEmpty()) {
      return List.of();
    }
    List<TeamInfo> result = new ArrayList<>(teams.size());
    for (GHTeam team : teams) {
      if (team == null) {
        continue;
      }
      result.add(
          new TeamInfo(
              team.getId(),
              safeTrim(team.getSlug()),
              safeTrim(team.getName()),
              safeTrim(team.getDescription())));
    }
    return List.copyOf(result);
  }

  private void validateReviewCommentLocation(ReviewCommentLocation location) {
    if (location == null) {
      return;
    }
    if (!StringUtils.hasText(location.path())) {
      throw new IllegalArgumentException("Review comment path must not be blank");
    }
    boolean hasLine = location.line() != null;
    boolean hasStartLine = location.startLine() != null;
    boolean hasEndLine = location.endLine() != null;
    boolean hasRange = hasStartLine && hasEndLine;
    boolean hasPosition = location.position() != null;
    if (hasStartLine ^ hasEndLine) {
      throw new IllegalArgumentException("Review comment range requires both startLine and endLine");
    }
    if (!(hasLine || hasRange || hasPosition)) {
      throw new IllegalArgumentException("Review comment location requires line, range or diff position");
    }
    if (hasLine && location.line() <= 0) {
      throw new IllegalArgumentException("Review comment line must be positive");
    }
    if (hasRange) {
      if (location.startLine() <= 0 || location.endLine() <= 0) {
        throw new IllegalArgumentException("Review comment range must be positive");
      }
      if (location.endLine() < location.startLine()) {
        throw new IllegalArgumentException("Review comment endLine must be >= startLine");
      }
    }
    if (hasPosition && location.position() <= 0) {
      throw new IllegalArgumentException("Review comment position must be positive");
    }
  }

  private int requirePositive(Integer value, String fieldName) {
    if (value == null || value <= 0) {
      throw new IllegalArgumentException(fieldName + " must be positive");
    }
    return value;
  }

  private long requirePositiveLong(Long value, String fieldName) {
    if (value == null || value <= 0) {
      throw new IllegalArgumentException(fieldName + " must be positive");
    }
    return value;
  }

  private GHIssueComment findMatchingIssueComment(GHPullRequest pr, String body) throws IOException {
    if (!StringUtils.hasText(body)) {
      return null;
    }
    int inspected = 0;
    for (GHIssueComment comment : pr.listComments().withPageSize(COMMENT_DEDUP_LIMIT)) {
      if (inspected++ >= COMMENT_DEDUP_LIMIT) {
        break;
      }
      if (Objects.equals(safeTrim(comment.getBody()), body)) {
        return comment;
      }
    }
    return null;
  }

  private GHPullRequestReviewComment findMatchingReviewComment(
      GHPullRequest pr, ReviewCommentLocation location, String body) throws IOException {
    int inspected = 0;
    for (GHPullRequestReviewComment comment : pr.listReviewComments().withPageSize(COMMENT_DEDUP_LIMIT)) {
      if (inspected++ >= COMMENT_DEDUP_LIMIT) {
        break;
      }
      if (!Objects.equals(safeTrim(comment.getBody()), body)) {
        continue;
      }
      if (matchesReviewLocation(comment, location)) {
        return comment;
      }
    }
    return null;
  }

  private boolean matchesReviewLocation(GHPullRequestReviewComment comment, ReviewCommentLocation location) {
    if (location == null) {
      return false;
    }
    if (!Objects.equals(safeTrim(comment.getPath()), safeTrim(location.path()))) {
      return false;
    }
    if (StringUtils.hasText(location.commitSha())) {
      if (!Objects.equals(location.commitSha().trim(), safeTrim(comment.getCommitId()))) {
        return false;
      }
    }
    Integer line = location.line();
    Integer startLine = location.startLine();
    Integer endLine = location.endLine();
    boolean hasRange = startLine != null && endLine != null;
    boolean multiLineRange = hasRange && !Objects.equals(startLine, endLine);
    boolean singleLineRange = hasRange && Objects.equals(startLine, endLine);
    if (line != null) {
      return comment.getLine() == line;
    }
    if (singleLineRange) {
      return comment.getLine() == endLine;
    }
    if (multiLineRange) {
      return comment.getStartLine() == startLine && comment.getLine() == endLine;
    }
    if (location.position() != null) {
      Integer target = location.position();
      return Objects.equals(target, comment.getPosition()) || Objects.equals(target, comment.getOriginalPosition());
    }
    return false;
  }

  private GHPullRequestReview findMatchingReview(
      GHPullRequest pr, GHPullRequestReviewState targetState, String body) throws IOException {
    int inspected = 0;
    for (GHPullRequestReview review : pr.listReviews().withPageSize(REVIEW_DEDUP_LIMIT)) {
      if (inspected++ >= REVIEW_DEDUP_LIMIT) {
        break;
      }
      GHPullRequestReviewState state = review.getState();
      if (targetState != null && state != null && state != targetState) {
        continue;
      }
      if (Objects.equals(safeTrim(review.getBody()), body)) {
        return review;
      }
    }
    return null;
  }

  private GHPullRequestReview findReviewById(GHPullRequest pr, long reviewId) throws IOException {
    for (GHPullRequestReview review : pr.listReviews()) {
      if (review.getId() == reviewId) {
        return review;
      }
    }
    return null;
  }

  private void appendDraft(GHPullRequestReviewBuilder builder, ReviewCommentDraft draft) {
    if (draft == null) {
      return;
    }
    String body = safeTrim(draft.body());
    if (!StringUtils.hasText(body)) {
      throw new IllegalArgumentException("Review comment draft body must not be blank");
    }
    String path = safeTrim(draft.path());
    if (!StringUtils.hasText(path)) {
      throw new IllegalArgumentException("Review comment draft path must not be blank");
    }
    if (draft.startLine() != null && draft.endLine() != null) {
      int start = draft.startLine();
      int end = draft.endLine();
      if (start <= 0 || end <= 0) {
        throw new IllegalArgumentException("Review comment draft range must be positive");
      }
      if (end < start) {
        throw new IllegalArgumentException("Review comment draft endLine must be >= startLine");
      }
      if (start == end) {
        builder.singleLineComment(body, path, end);
        return;
      }
      builder.multiLineComment(body, path, start, end);
      return;
    }
    if (draft.line() != null) {
      int line = draft.line();
      if (line <= 0) {
        throw new IllegalArgumentException("Review comment draft line must be positive");
      }
      builder.singleLineComment(body, path, line);
      return;
    }
    if (draft.position() != null) {
      int position = draft.position();
      if (position <= 0) {
        throw new IllegalArgumentException("Review comment draft position must be positive");
      }
      builder.comment(body, path, position);
      return;
    }
    throw new IllegalArgumentException("Review comment draft requires line, range or diff position");
  }

  private GHPullRequestReviewEvent parseReviewEvent(String value) {
    if (!StringUtils.hasText(value)) {
      return null;
    }
    try {
      return GHPullRequestReviewEvent.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      throw new IllegalArgumentException("Unsupported review event: " + value, ex);
    }
  }

  private GHPullRequestReviewState toReviewState(GHPullRequestReviewEvent event) {
    if (event == null) {
      return GHPullRequestReviewState.PENDING;
    }
    return switch (event) {
      case APPROVE -> GHPullRequestReviewState.APPROVED;
      case REQUEST_CHANGES -> GHPullRequestReviewState.CHANGES_REQUESTED;
      case COMMENT -> GHPullRequestReviewState.COMMENTED;
      case PENDING -> GHPullRequestReviewState.PENDING;
    };
  }

  private CreatePullRequestCommentResult toIssueCommentResult(
      RepositoryRef repository, int number, GHIssueComment comment, boolean created) {
    return new CreatePullRequestCommentResult(
        repository,
        number,
        CommentType.ISSUE,
        comment.getId(),
        null,
        safeUrl(comment.getHtmlUrl()),
        created);
  }

  private CreatePullRequestCommentResult toReviewCommentResult(
      RepositoryRef repository, int number, GHPullRequestReviewComment comment, boolean created) {
    Long reviewId = comment.getPullRequestReviewId();
    if (reviewId != null && reviewId <= 0) {
      reviewId = null;
    }
    return new CreatePullRequestCommentResult(
        repository,
        number,
        CommentType.REVIEW,
        comment.getId(),
        reviewId,
        safeUrl(comment.getHtmlUrl()),
        created);
  }

  private PullRequestReviewInfo toReviewInfo(GHPullRequestReview review) {
    if (review == null) {
      return null;
    }
    return new PullRequestReviewInfo(
        review.getId(),
        safeLower(review.getState()),
        safeUrl(review.getHtmlUrl()),
        safeInstant(review::getSubmittedAt));
  }

  private MilestoneInfo toMilestone(GHMilestone milestone) {
    if (milestone == null) {
      return null;
    }
    return new MilestoneInfo(
        milestone.getNumber(),
        safeTrim(milestone.getTitle()),
        safeLower(milestone.getState()),
        toInstant(milestone.getDueOn()),
        safeTrim(milestone.getDescription()));
  }

  private PullRequestIssueComment toIssueComment(GHIssueComment comment) {
    return new PullRequestIssueComment(
        comment.getId(),
        safeTrim(comment.getBody()),
        safeUser(comment::getUser),
        safeInstant(comment::getCreatedAt),
        safeInstant(comment::getUpdatedAt),
        safeUrl(comment.getHtmlUrl()));
  }

  private PullRequestReviewComment toReviewComment(GHPullRequestReviewComment comment) {
    return new PullRequestReviewComment(
        comment.getId(),
        safeTrim(comment.getBody()),
        safeTrim(comment.getDiffHunk()),
        safeUser(comment::getUser),
        safeTrim(comment.getPath()),
        comment.getPosition(),
        comment.getOriginalPosition(),
        comment.getStartLine(),
        comment.getLine(),
        safeLower(comment.getSide()),
        safeInstant(comment::getCreatedAt),
        safeInstant(comment::getUpdatedAt),
        safeUrl(comment.getHtmlUrl()));
  }

  private GitHubClientException wrapGitHubException(String message, IOException cause) {
    String detail = describeGitHubHttpError(cause);
    if (StringUtils.hasText(detail)) {
      message = message + " (" + detail + ")";
    }
    return new GitHubClientException(message, cause);
  }

  private String describeGitHubHttpError(Throwable throwable) {
    HttpException httpException = findHttpException(throwable);
    if (httpException == null) {
      return null;
    }
    StringBuilder detail = new StringBuilder();
    if (httpException.getResponseCode() > 0) {
      detail.append("status ").append(httpException.getResponseCode());
      if (StringUtils.hasText(httpException.getResponseMessage())) {
        detail.append(" ").append(httpException.getResponseMessage());
      }
    }
    String bodyDetail = extractGitHubErrorBody(httpException.getMessage());
    if (StringUtils.hasText(bodyDetail)) {
      if (detail.length() > 0) {
        detail.append(" - ");
      }
      detail.append(bodyDetail);
    }
    String result = detail.toString();
    if (result.length() > MAX_API_ERROR_DETAIL) {
      return result.substring(0, MAX_API_ERROR_DETAIL) + "...";
    }
    return result;
  }

  private HttpException findHttpException(Throwable throwable) {
    Throwable current = throwable;
    while (current != null) {
      if (current instanceof HttpException httpException) {
        return httpException;
      }
      current = current.getCause();
    }
    return null;
  }

  private String extractGitHubErrorBody(String rawBody) {
    if (!StringUtils.hasText(rawBody)) {
      return null;
    }
    String trimmed = rawBody.trim();
    if (trimmed.startsWith("{")) {
      try {
        JsonNode node = OBJECT_MAPPER.readTree(trimmed);
        List<String> parts = new ArrayList<>();
        String message = node.path("message").asText(null);
        if (StringUtils.hasText(message)) {
          parts.add(message);
        }
        JsonNode errors = node.get("errors");
        if (errors != null && errors.isArray()) {
          for (JsonNode errorNode : errors) {
            String errorDetail = extractGitHubErrorDetail(errorNode);
            if (StringUtils.hasText(errorDetail)) {
              parts.add(errorDetail);
            }
          }
        }
        if (!parts.isEmpty()) {
          return String.join("; ", parts);
        }
      } catch (JsonProcessingException ex) {
        // Ignore parsing failure and fall back to the raw payload
      }
    }
    return trimmed;
  }

  private String extractGitHubErrorDetail(JsonNode errorNode) {
    if (errorNode == null || errorNode.isNull()) {
      return null;
    }
    if (errorNode.isTextual()) {
      return errorNode.asText();
    }
    String message = errorNode.path("message").asText(null);
    if (!StringUtils.hasText(message)) {
      message = errorNode.path("code").asText(null);
    }
    String resource = errorNode.path("resource").asText(null);
    String field = errorNode.path("field").asText(null);
    StringBuilder builder = new StringBuilder();
    if (StringUtils.hasText(resource)) {
      builder.append(resource);
    }
    if (StringUtils.hasText(field)) {
      if (builder.length() > 0) {
        builder.append('.');
      }
      builder.append(field);
    }
    if (StringUtils.hasText(message)) {
      if (builder.length() > 0) {
        builder.append(": ");
      }
      builder.append(message);
    }
    String detail = builder.toString();
    if (StringUtils.hasText(detail)) {
      return detail;
    }
    return errorNode.toString();
  }

  private CheckRunInfo toCheckRun(GHCheckRun run) {
    if (run == null) {
      return null;
    }
    String status = run.getStatus() != null ? run.getStatus().toString().toLowerCase(Locale.ROOT) : null;
    String conclusion =
        run.getConclusion() != null ? run.getConclusion().toString().toLowerCase(Locale.ROOT) : null;
    String appSlug = run.getApp() != null ? safeTrim(run.getApp().getSlug()) : null;
    String appName = run.getApp() != null ? safeTrim(run.getApp().getName()) : null;
    return new CheckRunInfo(
        run.getId(),
        safeTrim(run.getName()),
        status,
        conclusion,
        safeTrim(run.getExternalId()),
        appSlug,
        appName,
        safeUrl(run.getHtmlUrl()),
        safeUrl(run.getDetailsUrl()),
        toInstant(run.getStartedAt()),
        toInstant(run.getCompletedAt()));
  }

  private CommitStatusInfo toCommitStatus(GHCommitStatus status) {
    if (status == null) {
      return null;
    }
    return new CommitStatusInfo(
        status.getId(),
        safeTrim(status.getContext()),
        status.getState() != null ? status.getState().toString().toLowerCase(Locale.ROOT) : null,
        safeTrim(status.getDescription()),
        status.getTargetUrl(),
        safeUser(status::getCreator),
        safeInstant(status::getCreatedAt),
        safeInstant(status::getUpdatedAt));
  }

  private UserInfo toUser(GHUser user) {
    if (user == null) {
      return null;
    }
    return new UserInfo(
        user.getId(),
        safeTrim(user.getLogin()),
        safeUrl(user.getHtmlUrl()),
        safeTrim(user.getAvatarUrl()));
  }

  private UserInfo safeUser(IOSupplier<GHUser> supplier) {
    if (supplier == null) {
      return null;
    }
    try {
      return toUser(supplier.get());
    } catch (IOException ex) {
      log.debug("Unable to load GitHub user: {}", ex.getMessage());
      return null;
    }
  }

  private Instant safeInstant(IOSupplier<Date> supplier) {
    if (supplier == null) {
      return null;
    }
    try {
      return toInstant(supplier.get());
    } catch (IOException ex) {
      log.debug("Unable to read timestamp: {}", ex.getMessage());
      return null;
    }
  }

  private Instant toInstant(Date date) {
    return date != null ? date.toInstant() : null;
  }

  private String safeUrl(URL url) {
    return url != null ? url.toString() : null;
  }

  private String safeTrim(String value) {
    return value != null ? value.trim() : null;
  }

  private String safeLower(String value) {
    return value != null ? value.trim().toLowerCase(Locale.ROOT) : null;
  }

  private String safeLower(Enum<?> value) {
    return value != null ? value.name().toLowerCase(Locale.ROOT) : null;
  }

  private GHIssueState parseIssueState(String state) {
    if (!StringUtils.hasText(state)) {
      return null;
    }
    try {
      return GHIssueState.valueOf(state.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      log.debug("Unsupported pull request state filter '{}'", state, ex);
      return null;
    }
  }

  private GHPullRequestQueryBuilder.Sort parseSort(String sort) {
    if (!StringUtils.hasText(sort)) {
      return null;
    }
    try {
      return GHPullRequestQueryBuilder.Sort.valueOf(sort.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      log.debug("Unsupported pull request sort '{}'", sort, ex);
      return null;
    }
  }

  private GHDirection parseDirection(String direction) {
    if (!StringUtils.hasText(direction)) {
      return null;
    }
    try {
      return GHDirection.valueOf(direction.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      log.debug("Unsupported pull request direction '{}'", direction, ex);
      return null;
    }
  }

  private TruncatedValue truncateUtf8(String value, long maxBytes) {
    if (value == null) {
      return new TruncatedValue("", false);
    }
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    if (bytes.length <= maxBytes) {
      return new TruncatedValue(value, false);
    }
    CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder();
    decoder.onMalformedInput(CodingErrorAction.IGNORE);
    decoder.onUnmappableCharacter(CodingErrorAction.IGNORE);
    ByteBuffer buffer = ByteBuffer.wrap(bytes, 0, (int) Math.min(bytes.length, maxBytes));
    try {
      CharBuffer decoded = decoder.decode(buffer);
      return new TruncatedValue(decoded.toString(), true);
    } catch (CharacterCodingException ex) {
      log.debug("Failed to decode truncated diff payload: {}", ex.getMessage());
      return new TruncatedValue(new String(bytes, 0, (int) Math.min(bytes.length, maxBytes), StandardCharsets.UTF_8), true);
    }
  }

  private TreeData fetchTree(org.kohsuke.github.GitHub github, RepositoryRef repository, int depth) {
    try {
      GHRepository repo = github.getRepository(repository.fullName());
      String ref = repository.ref();
      String commitSha = resolveCommitSha(repo, ref);
      GHTree tree = repo.getTreeRecursive(commitSha, depth);
      List<TreeEntry> entries =
          tree.getTree().stream()
              .map(entry -> toTreeEntry(repository, entry))
              .sorted(Comparator.comparing(TreeEntry::path))
              .toList();
      return new TreeData(repository.withRef(ref), commitSha, entries, tree.isTruncated());
    } catch (IOException ex) {
      throw new GitHubClientException(
          "Failed to fetch repository tree for %s".formatted(repository.fullName()), ex);
    }
  }

  private FileData fetchFile(org.kohsuke.github.GitHub github, RepositoryRef repository, String path) {
    try {
      GHRepository repo = github.getRepository(repository.fullName());
      GHContent content = repo.getFileContent(path, repository.ref());
      if (content == null) {
        throw new GitHubClientException(
            "GitHub returned null content for %s/%s".formatted(repository.fullName(), path));
      }
      long fileSize = content.getSize();
      Long maxSize = properties.getFileMaxSizeBytes();
      if (maxSize != null && fileSize > maxSize) {
        throw new GitHubClientException(
            "File size %d exceeds configured limit %d".formatted(fileSize, maxSize));
      }
      byte[] rawBytes = readContent(content);
      String base64 = Base64.getEncoder().encodeToString(rawBytes);
      String text = decodeUtf8(rawBytes);
      RepositoryFile file =
          new RepositoryFile(
              content.getPath(),
              content.getSha(),
              fileSize,
              content.getEncoding(),
              content.getDownloadUrl(),
              base64,
              text);
      return new FileData(repository.withRef(repository.ref()), repository.ref(), file);
    } catch (IOException ex) {
      throw new GitHubClientException(
          "Failed to fetch file %s from %s".formatted(path, repository.fullName()), ex);
    }
  }

  private byte[] readContent(GHContent content) throws IOException {
    try (var stream = content.read()) {
      byte[] bytes = stream.readAllBytes();
      if (bytes.length > 0) {
        return bytes;
      }
    }
    String payload = content.getContent();
    if (!StringUtils.hasText(payload)) {
      return new byte[0];
    }
    if ("base64".equalsIgnoreCase(content.getEncoding())) {
      return Base64.getDecoder().decode(payload);
    }
    return payload.getBytes(StandardCharsets.UTF_8);
  }

  private NormalizedFetchOptions normalizeFetchOptions(
      RepositoryRef repository, @Nullable GitFetchOptions options) {
    CheckoutStrategy strategy =
        options != null && options.strategy() != null
            ? options.strategy()
            : CheckoutStrategy.ARCHIVE_WITH_FALLBACK_CLONE;
    boolean includeSubmodules =
        options != null && Boolean.TRUE.equals(options.includeSubmodules());
    boolean shallowClone = options == null || !Boolean.FALSE.equals(options.shallowClone());
    Duration cloneTimeout =
        options != null && options.cloneTimeout() != null
            ? effectiveDuration(options.cloneTimeout(), Duration.ofMinutes(10))
            : Duration.ofMinutes(10);
    Duration archiveTimeout =
        options != null && options.archiveTimeout() != null
            ? effectiveDuration(options.archiveTimeout(), properties.getArchiveDownloadTimeout())
            : Optional.ofNullable(properties.getArchiveDownloadTimeout())
                .orElse(Duration.ofMinutes(2));
    long archiveSizeLimit =
        options != null && options.archiveSizeLimit() != null && options.archiveSizeLimit() > 0
            ? options.archiveSizeLimit()
            : Optional.ofNullable(properties.getArchiveMaxSizeBytes()).orElse(512L * 1024 * 1024);
    boolean detectKeyFiles =
        options == null || !Boolean.FALSE.equals(options.detectKeyFiles());

    if (strategy == CheckoutStrategy.CLONE_WITH_SUBMODULES) {
      includeSubmodules = true;
      shallowClone = false;
    }
    if (repository != null && isCommitSha(repository.ref())) {
      shallowClone = false;
    }
    return new NormalizedFetchOptions(
        strategy, shallowClone, includeSubmodules, cloneTimeout, archiveTimeout, archiveSizeLimit, detectKeyFiles);
  }

  private boolean isCommitSha(@Nullable String ref) {
    if (!StringUtils.hasText(ref)) {
      return false;
    }
    return ref.trim().matches("(?i)[0-9a-f]{40}");
  }

  private FetchExecutionOutcome performFetch(
      RepositoryRef repository,
      TempWorkspaceService.Workspace workspace,
      NormalizedFetchOptions options) {
    CheckoutStrategy strategy = options.strategy();
    if (strategy == CheckoutStrategy.ARCHIVE_ONLY
        || strategy == CheckoutStrategy.ARCHIVE_WITH_FALLBACK_CLONE) {
      try {
        return executor.execute(
            github ->
                downloadRepositoryArchive(github, repository, workspace, options, strategy));
      } catch (ArchiveDownloadException ex) {
        if (strategy == CheckoutStrategy.ARCHIVE_ONLY) {
          throw new GitHubClientException(
              "Archive fetch failed for %s: %s"
                  .formatted(repository.fullName(), ex.getMessage()),
              ex);
        }
        log.info(
            "Archive fetch for {} ({}) failed, falling back to git clone: {}",
            repository.fullName(),
            repository.ref(),
            ex.getMessage());
        emptyDirectory(workspace.path());
      } catch (GitHubClientException ex) {
        if (strategy == CheckoutStrategy.ARCHIVE_ONLY) {
          throw ex;
        }
        log.info(
            "Archive fetch for {} ({}) failed, falling back to git clone: {}",
            repository.fullName(),
            repository.ref(),
            ex.getMessage());
        emptyDirectory(workspace.path());
      }
    }
    return cloneRepository(repository, workspace, options);
  }

  private FetchExecutionOutcome downloadRepositoryArchive(
      org.kohsuke.github.GitHub github,
      RepositoryRef repository,
      TempWorkspaceService.Workspace workspace,
      NormalizedFetchOptions options,
      CheckoutStrategy requestedStrategy) {
    try {
      GHRepository repo = github.getRepository(repository.fullName());
      String ref = repository.ref();
      String commitSha = resolveCommitSha(repo, ref);
      emptyDirectory(workspace.path());
      long downloadedBytes =
          repo.readZip(
              stream ->
                  extractZipArchive(
                      stream,
                      workspace.path(),
                      options.archiveSizeLimit(),
                      workspaceService.getSizeLimitBytes()),
              ref);
      Instant completedAt = Instant.now();
      return new FetchExecutionOutcome(
          requestedStrategy, commitSha, commitSha, downloadedBytes, completedAt);
    } catch (ArchiveDownloadException ex) {
      throw ex;
    } catch (IOException ex) {
      throw new GitHubClientException(
          "Failed to download repository archive for %s".formatted(repository.fullName()), ex);
    }
  }

  private FetchExecutionOutcome cloneRepository(
      RepositoryRef repository,
      TempWorkspaceService.Workspace workspace,
      NormalizedFetchOptions options) {
    Path workspacePath = workspace.path();
    Path root = workspacePath.getParent();
    if (root == null) {
      throw new GitHubClientException(
          "Workspace path has no parent directory: " + workspacePath);
    }
    emptyDirectory(workspacePath);
    String token = tokenManager.currentToken();
    String authHeader = authorizationHeader(token);
    String branchName = extractBranchName(repository.ref());

    List<String> command = new ArrayList<>();
    command.add("git");
    command.add("clone");
    if (options.shallowClone()) {
      command.add("--depth=1");
    }
    if (options.includeSubmodules()) {
      command.add("--recurse-submodules");
    }
    if (StringUtils.hasText(branchName) && !isCommitSha(repository.ref())) {
      command.add("--branch");
      command.add(branchName);
    }
    command.add(buildCloneUrl(repository));
    command.add(workspacePath.getFileName().toString());

    ProcessBuilder builder = new ProcessBuilder(command);
    builder.directory(root.toFile());
    builder.redirectErrorStream(true);
    builder.environment().put("GIT_TERMINAL_PROMPT", "0");
    builder.environment().putIfAbsent("LC_ALL", "C");
    builder.environment().put("GIT_HTTP_EXTRAHEADER", authHeader);

    ProcessResult cloneResult = runProcess(builder, options.cloneTimeout());
    if (cloneResult.exitCode() != 0) {
      throw new GitHubClientException(
          "git clone failed: " + sanitizeProcessOutput(cloneResult.output(), token));
    }

    String ref = repository.ref();
    Duration gitTimeout = options.cloneTimeout();
    if (isCommitSha(ref)) {
      runGitCommand(
          workspacePath, gitTimeout, authHeader, token, "git", "fetch", "origin", ref);
      runGitCommand(workspacePath, gitTimeout, authHeader, token, "git", "checkout", ref);
    } else if (StringUtils.hasText(ref) && ref.startsWith("refs/")) {
      String target = normalizeGitRef(ref);
      if (StringUtils.hasText(target)) {
        runGitCommand(workspacePath, gitTimeout, authHeader, token, "git", "checkout", target);
      }
    }

    ProcessResult headResult =
        runGitCommand(workspacePath, Duration.ofSeconds(30), authHeader, token, "git", "rev-parse", "HEAD");
    String commitSha = headResult.output().trim();
    Instant completedAt = Instant.now();
    CheckoutStrategy applied =
        options.strategy() == CheckoutStrategy.CLONE_WITH_SUBMODULES
            ? CheckoutStrategy.CLONE_WITH_SUBMODULES
            : CheckoutStrategy.ARCHIVE_WITH_FALLBACK_CLONE;
    return new FetchExecutionOutcome(applied, commitSha, commitSha, 0L, completedAt);
  }

  private long extractZipArchive(
      InputStream stream,
      Path workspacePath,
      long archiveSizeLimit,
      long workspaceSizeLimit) {
    byte[] buffer = new byte[8192];
    long written = 0L;
    String rootPrefix = null;
    try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(stream))) {
      ZipEntry entry;
      while ((entry = zip.getNextEntry()) != null) {
        String rawName = entry.getName();
        if (!StringUtils.hasText(rawName)) {
          continue;
        }
        String normalizedName = rawName.replace('\\', '/');
        if (rootPrefix == null) {
          int slashIndex = normalizedName.indexOf('/');
          rootPrefix = slashIndex >= 0 ? normalizedName.substring(0, slashIndex + 1) : "";
        }
        String relativeName = stripRoot(normalizedName, rootPrefix);
        if (!StringUtils.hasText(relativeName)) {
          continue;
        }
        if (relativeName.contains("..")) {
          throw new ArchiveDownloadException(
              "Archive entry contains illegal path traversal: " + relativeName);
        }
        Path target = workspacePath.resolve(relativeName).normalize();
        ensurePathWithin(workspacePath, target);
        if (entry.isDirectory()) {
          Files.createDirectories(target);
          continue;
        }
        Files.createDirectories(target.getParent());
        try (OutputStream out = Files.newOutputStream(target)) {
          int read;
          while ((read = zip.read(buffer)) != -1) {
            out.write(buffer, 0, read);
            written += read;
            if (archiveSizeLimit > 0 && written > archiveSizeLimit) {
              throw new ArchiveDownloadException(
                  "Archive extracted size exceeds configured limit %d bytes"
                      .formatted(archiveSizeLimit));
            }
            if (workspaceSizeLimit > 0 && written > workspaceSizeLimit) {
              throw new ArchiveDownloadException(
                  "Workspace size exceeds configured limit %d bytes"
                      .formatted(workspaceSizeLimit));
            }
          }
        }
        try {
          if (entry.getLastModifiedTime() != null) {
            Files.setLastModifiedTime(target, entry.getLastModifiedTime());
          }
        } catch (Exception ex) {
          log.debug("Unable to apply mtime for {}: {}", target, ex.getMessage());
        }
      }
    } catch (ArchiveDownloadException ex) {
      throw ex;
    } catch (IOException ex) {
      throw new ArchiveDownloadException("Failed to extract repository archive", ex);
    }
    return written;
  }

  private void ensurePathWithin(Path root, Path target) {
    Path normalizedRoot = root.toAbsolutePath().normalize();
    Path normalizedTarget = target.toAbsolutePath().normalize();
    if (!normalizedTarget.startsWith(normalizedRoot)) {
      throw new ArchiveDownloadException(
          "Archive entry resolved outside workspace root: " + normalizedTarget);
    }
  }

  private String stripRoot(String entryName, String rootPrefix) {
    String result = entryName;
    while (result.startsWith("/")) {
      result = result.substring(1);
    }
    if (StringUtils.hasText(rootPrefix) && result.startsWith(rootPrefix)) {
      result = result.substring(rootPrefix.length());
    }
    return result;
  }

  private ProcessResult runGitCommand(
      Path workdir,
      Duration timeout,
      String authHeader,
      String token,
      String... command) {
    ProcessBuilder builder = new ProcessBuilder(command);
    builder.directory(workdir.toFile());
    builder.redirectErrorStream(true);
    builder.environment().put("GIT_TERMINAL_PROMPT", "0");
    builder.environment().putIfAbsent("LC_ALL", "C");
    if (StringUtils.hasText(authHeader)) {
      builder.environment().put("GIT_HTTP_EXTRAHEADER", authHeader);
    }
    ProcessResult result = runProcess(builder, timeout);
    if (result.exitCode() != 0) {
      throw new GitHubClientException(
          "%s failed: %s"
              .formatted(String.join(" ", command), sanitizeProcessOutput(result.output(), token)));
    }
    return result;
  }

  private ProcessResult runProcess(ProcessBuilder builder, Duration timeout) {
    Duration effectiveTimeout =
        timeout != null && !timeout.isNegative() && !timeout.isZero()
            ? timeout
            : Duration.ofMinutes(5);
    try {
      Process process = builder.start();
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      Thread reader =
          new Thread(
              () -> {
                try (InputStream in = process.getInputStream()) {
                  in.transferTo(output);
                } catch (IOException ex) {
                  log.debug("Failed to read process output: {}", ex.getMessage());
                }
              },
              "github-fetch-process");
      reader.setDaemon(true);
      reader.start();
      boolean finished =
          process.waitFor(effectiveTimeout.toMillis(), TimeUnit.MILLISECONDS);
      if (!finished) {
        process.destroyForcibly();
        reader.join(TimeUnit.SECONDS.toMillis(5));
        throw new GitHubClientException(
            "Command %s timed out after %d seconds"
                .formatted(String.join(" ", builder.command()), effectiveTimeout.toSeconds()));
      }
      reader.join(TimeUnit.SECONDS.toMillis(5));
      return new ProcessResult(process.exitValue(), output.toString(StandardCharsets.UTF_8));
    } catch (IOException ex) {
      throw new GitHubClientException(
          "Failed to start command " + String.join(" ", builder.command()), ex);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new GitHubClientException("Command interrupted", ex);
    }
  }

  private String authorizationHeader(String token) {
    if (!StringUtils.hasText(token)) {
      return "";
    }
    String credentials = "x-access-token:" + token;
    String encoded = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    return "Authorization: Basic " + encoded;
  }

  private String sanitizeProcessOutput(String output, String token) {
    if (output == null) {
      return "";
    }
    String sanitized = output;
    if (StringUtils.hasText(token)) {
      sanitized = sanitized.replace(token, "****");
      String encoded =
          Base64.getEncoder()
              .encodeToString(("x-access-token:" + token).getBytes(StandardCharsets.UTF_8));
      sanitized = sanitized.replace(encoded, "****");
    }
    return sanitized.trim();
  }

  private String extractBranchName(String ref) {
    if (!StringUtils.hasText(ref)) {
      return null;
    }
    String trimmed = ref.trim();
    if (trimmed.startsWith("refs/heads/")) {
      return trimmed.substring("refs/heads/".length());
    }
    if (trimmed.startsWith("heads/")) {
      return trimmed.substring("heads/".length());
    }
    if (trimmed.startsWith("refs/tags/")) {
      return trimmed.substring("refs/".length());
    }
    if (trimmed.startsWith("tags/")) {
      return trimmed;
    }
    if (isCommitSha(trimmed)) {
      return null;
    }
    return trimmed;
  }

  private String normalizeGitRef(String ref) {
    if (!StringUtils.hasText(ref)) {
      return null;
    }
    String trimmed = ref.trim();
    if (trimmed.startsWith("refs/heads/")) {
      return trimmed.substring("refs/heads/".length());
    }
    if (trimmed.startsWith("refs/tags/")) {
      return "tags/" + trimmed.substring("refs/tags/".length());
    }
    if (trimmed.startsWith("heads/") || trimmed.startsWith("tags/")) {
      return trimmed;
    }
    return trimmed;
  }

  private String buildCloneUrl(RepositoryRef repository) {
    String override = properties.getCloneBaseUrl();
    if (StringUtils.hasText(override)) {
      return formatCloneOverride(override, repository);
    }

    String baseUrl = properties.getBaseUrl();
    if (!StringUtils.hasText(baseUrl) || baseUrl.contains("api.github.com")) {
      return "https://github.com/" + repository.fullName() + ".git";
    }
    String sanitized = baseUrl.trim();
    if (sanitized.endsWith("/")) {
      sanitized = sanitized.substring(0, sanitized.length() - 1);
    }
    if (sanitized.endsWith("/api/v3")) {
      sanitized = sanitized.substring(0, sanitized.length() - "/api/v3".length());
    }
    if (!sanitized.endsWith("/")) {
      sanitized = sanitized + "/";
    }
    return sanitized + repository.fullName() + ".git";
  }

  private String formatCloneOverride(String override, RepositoryRef repository) {
    String result = override;
    if (result.contains("{owner}")) {
      result = result.replace("{owner}", repository.owner());
    }
    if (result.contains("{name}")) {
      result = result.replace("{name}", repository.name());
    }
    if (result.contains("{fullName}")) {
      result = result.replace("{fullName}", repository.fullName());
    }
    if (result.contains("%s")) {
      return result.formatted(repository.fullName());
    }
    if (result.contains("%1$s") || result.contains("%2$s")) {
      return result.formatted(repository.owner(), repository.name());
    }
    return result;
  }

  private void emptyDirectory(Path directory) {
    try {
      if (Files.exists(directory)) {
        Files.walkFileTree(
            directory,
            new SimpleFileVisitor<>() {
              @Override
              public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                  throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
              }

              @Override
              public FileVisitResult postVisitDirectory(Path dir, IOException exc)
                  throws IOException {
                if (!dir.equals(directory)) {
                  Files.deleteIfExists(dir);
                }
                return FileVisitResult.CONTINUE;
              }
            });
      }
      Files.createDirectories(directory);
    } catch (IOException ex) {
      throw new GitHubClientException(
          "Failed to prepare workspace directory " + directory, ex);
    }
  }

  private long calculateWorkspaceSize(Path workspacePath) {
    final long[] size = {0L};
    try {
      Files.walkFileTree(
          workspacePath,
          new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                throws IOException {
              size[0] += attrs.size();
              return FileVisitResult.CONTINUE;
            }
          });
    } catch (IOException ex) {
      throw new GitHubClientException(
          "Failed to calculate workspace size for " + workspacePath, ex);
    }
    return size[0];
  }

  private List<String> collectKeyFiles(Path workspacePath) {
    try (Stream<Path> stream = Files.list(workspacePath)) {
      return stream
          .filter(path -> !".".equals(path.getFileName().toString()))
          .filter(path -> !".git".equals(path.getFileName().toString()))
          .map(path -> workspacePath.relativize(path).toString())
          .sorted()
          .limit(25)
          .toList();
    } catch (IOException ex) {
      throw new GitHubClientException(
          "Failed to inspect workspace directory " + workspacePath, ex);
    }
  }

  private Duration effectiveDuration(Duration candidate, Duration fallback) {
    if (candidate == null || candidate.isZero() || candidate.isNegative()) {
      return fallback != null ? fallback : Duration.ofMinutes(1);
    }
    return candidate;
  }

  record CreateBranchInput(RepositoryRef repository, String workspaceId, String branchName, String sourceSha) {}

  record CreateBranchResult(
      RepositoryRef repository,
      String workspaceId,
      String branchName,
      String branchRef,
      String sourceSha,
      String localHeadSha,
      Instant createdAt) {}

  record CommitWorkspaceDiffInput(
      String workspaceId, String branchName, CommitAuthor author, String commitMessage) {}

  record CommitWorkspaceDiffResult(
      RepositoryRef repository,
      String workspaceId,
      String branchName,
      String commitSha,
      int filesChanged,
      int additions,
      int deletions,
      List<CommitFileChange> files,
      Instant committedAt) {}

  record CommitAuthor(String name, String email) {}

  record CommitFileChange(String status, String path, String previousPath) {}

  private record CommitStatistics(int filesChanged, int additions, int deletions) {}

  record PushBranchInput(
      RepositoryRef repository, String workspaceId, String branchName, Boolean force) {}

  record PushBranchResult(
      RepositoryRef repository,
      String workspaceId,
      String branchName,
      String localHeadSha,
      String remoteHeadSha,
      int commitsPushed,
      Instant pushedAt) {}

  record OpenPullRequestInput(
      RepositoryRef repository,
      String headBranch,
      String baseBranch,
      String title,
      String body,
      List<String> reviewers,
      List<String> teamReviewers,
      Boolean draft) {}

  record OpenPullRequestResult(
      RepositoryRef repository,
      int pullRequestNumber,
      String htmlUrl,
      String headSha,
      String baseSha,
      Instant createdAt) {}

  record ApprovePullRequestInput(RepositoryRef repository, Integer number, String body) {}

  record ApprovePullRequestResult(
      RepositoryRef repository, int pullRequestNumber, long reviewId, String state, Instant submittedAt) {}

  record MergePullRequestInput(
      RepositoryRef repository,
      Integer number,
      MergeMethod method,
      String commitTitle,
      String commitMessage) {}

  record MergePullRequestResult(
      RepositoryRef repository,
      int pullRequestNumber,
      boolean merged,
      String mergeSha,
      String message,
      Instant mergedAt) {}

  enum MergeMethod {
    MERGE,
    SQUASH,
    REBASE
  }

  record FetchRepositoryInput(RepositoryRef repository, GitFetchOptions options, String requestId) {}

  record FetchRepositoryResult(
      RepositoryRef repository,
      String workspaceId,
      Path workspacePath,
      String resolvedRef,
      String commitSha,
      long downloadedBytes,
      long workspaceSizeBytes,
      Duration downloadDuration,
      CheckoutStrategy strategy,
      List<String> keyFiles,
      Instant fetchedAt) {}

  record GitFetchOptions(
      CheckoutStrategy strategy,
      Boolean shallowClone,
      Boolean includeSubmodules,
      Duration cloneTimeout,
      Duration archiveTimeout,
      Long archiveSizeLimit,
      Boolean detectKeyFiles) {}

  enum CheckoutStrategy {
    ARCHIVE_ONLY,
    ARCHIVE_WITH_FALLBACK_CLONE,
    CLONE_WITH_SUBMODULES
  }

  private record NormalizedFetchOptions(
      CheckoutStrategy strategy,
      boolean shallowClone,
      boolean includeSubmodules,
      Duration cloneTimeout,
      Duration archiveTimeout,
      long archiveSizeLimit,
      boolean detectKeyFiles) {}

  private record FetchExecutionOutcome(
      CheckoutStrategy strategy,
      String resolvedRef,
      String commitSha,
      long downloadedBytes,
      Instant completedAt) {}

  private record ProcessResult(int exitCode, String output) {}

  private static class ArchiveDownloadException extends RuntimeException {
    ArchiveDownloadException(String message) {
      super(message);
    }

    ArchiveDownloadException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  private RepositoryRef normalizeRepository(RepositoryRef repo) {
    if (repo == null) {
      throw new IllegalArgumentException("Repository reference must be provided");
    }
    if (!StringUtils.hasText(repo.owner()) || !StringUtils.hasText(repo.name())) {
      throw new IllegalArgumentException("Repository owner and name must be provided");
    }
    String owner = repo.owner().trim();
    String name = repo.name().trim();
    String ref = StringUtils.hasText(repo.ref()) ? repo.ref().trim() : "heads/main";
    return new RepositoryRef(owner, name, ref);
  }

  private String normalizePath(String path) {
    if (!StringUtils.hasText(path)) {
      return "";
    }
    String normalized = path.trim();
    if (normalized.startsWith("/")) {
      normalized = normalized.substring(1);
    }
    while (normalized.endsWith("/")) {
      normalized = normalized.substring(0, normalized.length() - 1);
    }
    return normalized;
  }

  private TreeEntry toTreeEntry(RepositoryRef repository, GHTreeEntry entry) {
    String type = entry.getType();
    TreeEntryType entryType = mapType(type);
    return new TreeEntry(
        entry.getPath(),
        entryType,
        entry.getSha(),
        entry.getSize());
  }

  private FilteredTree filterTree(
      List<TreeEntry> entries, String path, boolean recursive, int maxEntries) {
    List<TreeEntry> filtered = new ArrayList<>();
    boolean reachedLimit = false;
    if (!StringUtils.hasText(path)) {
      for (TreeEntry entry : entries) {
        if (!recursive && entry.path().contains("/")) {
          continue;
        }
        filtered.add(entry);
        if (filtered.size() >= maxEntries) {
          reachedLimit = true;
          break;
        }
      }
      return new FilteredTree(List.copyOf(filtered), reachedLimit);
    }
    String prefix = path + "/";
    for (TreeEntry entry : entries) {
      if (!entry.path().equals(path) && !entry.path().startsWith(prefix)) {
        continue;
      }
      if (!recursive) {
        String relative = entry.path().equals(path) ? "" : entry.path().substring(prefix.length());
        if (relative.contains("/")) {
          continue;
        }
      }
      filtered.add(entry);
      if (filtered.size() >= maxEntries) {
        reachedLimit = true;
        break;
      }
    }
    return new FilteredTree(List.copyOf(filtered), reachedLimit);
  }

  private TreeEntryType mapType(String type) {
    if (!StringUtils.hasText(type)) {
      return TreeEntryType.UNKNOWN;
    }
    return switch (type.toLowerCase(Locale.ROOT)) {
      case "tree" -> TreeEntryType.DIRECTORY;
      case "blob" -> TreeEntryType.FILE;
      case "commit" -> TreeEntryType.SUBMODULE;
      case "symlink" -> TreeEntryType.SYMLINK;
      default -> TreeEntryType.UNKNOWN;
    };
  }

  private String resolveCommitSha(GHRepository repository, String ref) throws IOException {
    if (!StringUtils.hasText(ref)) {
      GHRef defaultRef = repository.getRef("heads/" + repository.getDefaultBranch());
      return defaultRef.getObject().getSha();
    }
    String trimmed = ref.trim();
    if (trimmed.startsWith("refs/")) {
      GHRef ghRef = repository.getRef(trimmed);
      return ghRef.getObject().getSha();
    }
    if (trimmed.matches("[0-9a-fA-F]{40}")) {
      return trimmed;
    }
    try {
      GHRef ghRef = repository.getRef("heads/" + trimmed);
      return ghRef.getObject().getSha();
    } catch (IOException ex) {
      log.debug("Failed to resolve ref '{}' as branch, attempting commit lookup", trimmed, ex);
      return repository.getCommit(trimmed).getSHA1();
    }
  }

  private Duration safeTtl(Duration ttl, Duration defaultTtl) {
    if (ttl == null || ttl.isZero() || ttl.isNegative()) {
      return defaultTtl;
    }
    return ttl;
  }

  private Instant expiry(Duration ttl) {
    Duration effective = safeTtl(ttl, Duration.ofMinutes(1));
    return Instant.now().plus(effective);
  }

  private String decodeUtf8(byte[] bytes) {
    if (bytes == null || bytes.length == 0) {
      return "";
    }
    CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder();
    decoder.onMalformedInput(CodingErrorAction.REPORT);
    decoder.onUnmappableCharacter(CodingErrorAction.REPORT);
    try {
      return decoder.decode(ByteBuffer.wrap(bytes)).toString();
    } catch (CharacterCodingException ex) {
      log.debug("File content is not valid UTF-8: {}", ex.getMessage());
      return null;
    }
  }

  private String treeCacheKey(RepositoryRef repository, int depth) {
    return repository.fullName().toLowerCase(Locale.ROOT) + "@" + repository.ref() + ":d" + depth;
  }

  private String fileCacheKey(RepositoryRef repository, String path) {
    return repository.fullName().toLowerCase(Locale.ROOT) + "@" + repository.ref() + ":f:" + path;
  }

  record RepositoryRef(String owner, String name, String ref) {
    RepositoryRef withRef(String ref) {
      return new RepositoryRef(owner, name, ref);
    }

    String fullName() {
      return owner + "/" + name;
    }
  }

  record ListPullRequestsInput(
      RepositoryRef repository, String state, String head, String base, Integer limit, String sort, String direction) {}

  record ListPullRequestsResult(RepositoryRef repository, List<PullRequestSummary> pullRequests, boolean truncated) {}

  record PullRequestSummary(
      int number,
      String title,
      String state,
      boolean draft,
      boolean merged,
      UserInfo author,
      String baseRef,
      String headRef,
      String headSha,
      Instant createdAt,
      Instant updatedAt,
      Instant closedAt,
      Instant mergedAt,
      String htmlUrl) {}

  record GetPullRequestInput(RepositoryRef repository, Integer number) {}

  record PullRequestDetailsResult(RepositoryRef repository, PullRequestDetails pullRequest) {}

  record PullRequestDetails(
      PullRequestSummary summary,
      String body,
      List<LabelInfo> labels,
      List<UserInfo> assignees,
      List<UserInfo> requestedReviewers,
      List<TeamInfo> requestedTeams,
      MilestoneInfo milestone,
      MergeInfo merge,
      Boolean maintainerCanModify,
      String mergeCommitSha) {}

  record LabelInfo(String name, String color, String description) {}

  record UserInfo(Long id, String login, String htmlUrl, String avatarUrl) {}

  record TeamInfo(Long id, String slug, String name, String description) {}

  record MilestoneInfo(Integer number, String title, String state, Instant dueOn, String description) {}

  record MergeInfo(Boolean mergeable, String mergeableState, Boolean draft) {}

  record CreatePullRequestCommentInput(
      RepositoryRef repository, Integer number, String body, ReviewCommentLocation reviewLocation) {}

  record ReviewCommentLocation(
      String commitSha, String path, Integer line, Integer startLine, Integer endLine, Integer position) {}

  enum CommentType {
    ISSUE,
    REVIEW
  }

  record CreatePullRequestCommentResult(
      RepositoryRef repository,
      int pullRequestNumber,
      CommentType type,
      long commentId,
      Long reviewId,
      String htmlUrl,
      boolean created) {}

  record CreatePullRequestReviewInput(
      RepositoryRef repository,
      Integer number,
      String body,
      String commitId,
      String event,
      List<ReviewCommentDraft> comments) {}

  record ReviewCommentDraft(
      String body, String path, Integer line, Integer startLine, Integer endLine, Integer position) {}

  record CreatePullRequestReviewResult(
      RepositoryRef repository, int pullRequestNumber, PullRequestReviewInfo review, boolean created) {}

  record PullRequestReviewInfo(long id, String state, String htmlUrl, Instant submittedAt) {}

  record SubmitPullRequestReviewInput(
      RepositoryRef repository, Integer number, Long reviewId, String body, String event) {}

  record SubmitPullRequestReviewResult(
      RepositoryRef repository, int pullRequestNumber, PullRequestReviewInfo review) {}

  record GetPullRequestDiffInput(RepositoryRef repository, Integer number, Long maxBytes) {}

  record PullRequestDiffResult(RepositoryRef repository, String headSha, String diff, boolean truncated) {}

  record ListPullRequestCommentsInput(
      RepositoryRef repository, Integer number, Integer issueCommentLimit, Integer reviewCommentLimit) {}

  record PullRequestIssueComment(
      long id,
      String body,
      UserInfo author,
      Instant createdAt,
      Instant updatedAt,
      String htmlUrl) {}

  record PullRequestReviewComment(
      long id,
      String body,
      String diffHunk,
      UserInfo author,
      String path,
      Integer position,
      Integer originalPosition,
      Integer startLine,
      Integer line,
      String side,
      Instant createdAt,
      Instant updatedAt,
      String htmlUrl) {}

  record PullRequestCommentsResult(
      RepositoryRef repository,
      List<PullRequestIssueComment> issueComments,
      List<PullRequestReviewComment> reviewComments,
      boolean issueCommentsTruncated,
      boolean reviewCommentsTruncated) {}

  record ListPullRequestChecksInput(
      RepositoryRef repository, Integer number, Integer checkRunLimit, Integer statusLimit) {}

  record CheckRunInfo(
      long id,
      String name,
      String status,
      String conclusion,
      String externalId,
      String appSlug,
      String appName,
      String htmlUrl,
      String detailsUrl,
      Instant startedAt,
      Instant completedAt) {}

  record CommitStatusInfo(
      long id,
      String context,
      String state,
      String description,
      String targetUrl,
      UserInfo creator,
      Instant createdAt,
      Instant updatedAt) {}

  record PullRequestChecksResult(
      RepositoryRef repository,
      int pullRequestNumber,
      String headSha,
      String overallStatus,
      List<CheckRunInfo> checkRuns,
      List<CommitStatusInfo> statuses,
      boolean checkRunsTruncated,
      boolean statusesTruncated) {}

  record ListRepositoryTreeInput(
      RepositoryRef repository, String path, Boolean recursive, Integer maxDepth, Integer maxEntries) {}

  record ListRepositoryTreeResult(
      RepositoryRef repository, String resolvedRef, List<TreeEntry> entries, boolean truncated) {}

  record TreeEntry(String path, TreeEntryType type, String sha, long size) {}

  enum TreeEntryType {
    FILE,
    DIRECTORY,
    SUBMODULE,
    SYMLINK,
    UNKNOWN
  }

  record ReadFileInput(RepositoryRef repository, String path) {}

  record ReadFileResult(RepositoryRef repository, String resolvedRef, RepositoryFile file) {}

  record RepositoryFile(
      String path,
      String sha,
      long size,
      String encoding,
      String downloadUrl,
      String contentBase64,
      String textContent) {}

  private record PullRequestListData(List<PullRequestSummary> pullRequests, boolean truncated) {}

  private record PullRequestDetailsData(PullRequestDetails pullRequest) {}

  private record PullRequestDiffData(String ref, String diff, boolean truncated) {}

  private record PullRequestCommentsData(
      List<PullRequestIssueComment> issueComments,
      List<PullRequestReviewComment> reviewComments,
      boolean truncatedIssue,
      boolean truncatedReview) {}

  private record PullRequestChecksData(
      int pullRequestNumber,
      String headSha,
      String overallStatus,
      List<CheckRunInfo> checkRuns,
      List<CommitStatusInfo> statuses,
      boolean truncatedCheckRuns,
      boolean truncatedStatuses) {}

  @FunctionalInterface
  private interface IOSupplier<T> {
    T get() throws IOException;
  }

  private record TreeData(RepositoryRef repository, String resolvedRef, List<TreeEntry> entries, boolean truncated) {}

  private record FileData(RepositoryRef repository, String ref, RepositoryFile file) {}

  private record FilteredTree(List<TreeEntry> entries, boolean reachedLimit) {}

  private record CachedTree(TreeData payload, Instant expiresAt) {
    boolean isExpired() {
      return Instant.now().isAfter(expiresAt);
    }
  }

  private record CachedFile(FileData payload, Instant expiresAt) {
    boolean isExpired() {
      return Instant.now().isAfter(expiresAt);
    }
  }

  private record TruncatedValue(String value, boolean truncated) {}
}
