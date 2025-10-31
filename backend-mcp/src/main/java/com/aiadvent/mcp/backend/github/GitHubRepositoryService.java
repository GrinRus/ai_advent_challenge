package com.aiadvent.mcp.backend.github;

import com.aiadvent.mcp.backend.config.GitHubBackendProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
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
import org.kohsuke.github.GHCheckRun;
import org.kohsuke.github.GHCommit;
import org.kohsuke.github.GHCommitStatus;
import org.kohsuke.github.GHContent;
import org.kohsuke.github.GHDirection;
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
import org.kohsuke.github.HttpException;
import org.kohsuke.github.GHRef;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GHTeam;
import org.kohsuke.github.GHTree;
import org.kohsuke.github.GHTreeEntry;
import org.kohsuke.github.GHUser;
import org.kohsuke.github.PagedIterable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
class GitHubRepositoryService {

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
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private final GitHubClientExecutor executor;
  private final GitHubBackendProperties properties;

  private final Map<String, CachedTree> treeCache = new ConcurrentHashMap<>();
  private final Map<String, CachedFile> fileCache = new ConcurrentHashMap<>();

  GitHubRepositoryService(
      GitHubClientExecutor executor, GitHubBackendProperties properties) {
    this.executor = Objects.requireNonNull(executor, "executor");
    this.properties = Objects.requireNonNull(properties, "properties");
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
