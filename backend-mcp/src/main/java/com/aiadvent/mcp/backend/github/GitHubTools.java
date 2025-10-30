package com.aiadvent.mcp.backend.github;

import com.aiadvent.mcp.backend.github.GitHubRepositoryService.CheckRunInfo;
import com.aiadvent.mcp.backend.github.GitHubRepositoryService.CommentType;
import com.aiadvent.mcp.backend.github.GitHubRepositoryService.CommitStatusInfo;
import com.aiadvent.mcp.backend.github.GitHubRepositoryService.CreatePullRequestCommentInput;
import com.aiadvent.mcp.backend.github.GitHubRepositoryService.CreatePullRequestCommentResult;
import com.aiadvent.mcp.backend.github.GitHubRepositoryService.CreatePullRequestReviewInput;
import com.aiadvent.mcp.backend.github.GitHubRepositoryService.CreatePullRequestReviewResult;
import com.aiadvent.mcp.backend.github.GitHubRepositoryService.GetPullRequestDiffInput;
import com.aiadvent.mcp.backend.github.GitHubRepositoryService.GetPullRequestInput;
import com.aiadvent.mcp.backend.github.GitHubRepositoryService.ListPullRequestChecksInput;
import com.aiadvent.mcp.backend.github.GitHubRepositoryService.ListPullRequestCommentsInput;
import com.aiadvent.mcp.backend.github.GitHubRepositoryService.ListPullRequestsInput;
import com.aiadvent.mcp.backend.github.GitHubRepositoryService.ListPullRequestsResult;
import com.aiadvent.mcp.backend.github.GitHubRepositoryService.ListRepositoryTreeInput;
import com.aiadvent.mcp.backend.github.GitHubRepositoryService.ListRepositoryTreeResult;
import com.aiadvent.mcp.backend.github.GitHubRepositoryService.MergeInfo;
import com.aiadvent.mcp.backend.github.GitHubRepositoryService.MilestoneInfo;
import com.aiadvent.mcp.backend.github.GitHubRepositoryService.PullRequestChecksResult;
import com.aiadvent.mcp.backend.github.GitHubRepositoryService.PullRequestCommentsResult;
import com.aiadvent.mcp.backend.github.GitHubRepositoryService.PullRequestDetails;
import com.aiadvent.mcp.backend.github.GitHubRepositoryService.PullRequestDetailsResult;
import com.aiadvent.mcp.backend.github.GitHubRepositoryService.PullRequestDiffResult;
import com.aiadvent.mcp.backend.github.GitHubRepositoryService.PullRequestIssueComment;
import com.aiadvent.mcp.backend.github.GitHubRepositoryService.PullRequestReviewComment;
import com.aiadvent.mcp.backend.github.GitHubRepositoryService.PullRequestReviewInfo;
import com.aiadvent.mcp.backend.github.GitHubRepositoryService.PullRequestSummary;
import com.aiadvent.mcp.backend.github.GitHubRepositoryService.ReadFileInput;
import com.aiadvent.mcp.backend.github.GitHubRepositoryService.ReadFileResult;
import com.aiadvent.mcp.backend.github.GitHubRepositoryService.RepositoryFile;
import com.aiadvent.mcp.backend.github.GitHubRepositoryService.RepositoryRef;
import com.aiadvent.mcp.backend.github.GitHubRepositoryService.ReviewCommentDraft;
import com.aiadvent.mcp.backend.github.GitHubRepositoryService.ReviewCommentLocation;
import com.aiadvent.mcp.backend.github.GitHubRepositoryService.SubmitPullRequestReviewInput;
import com.aiadvent.mcp.backend.github.GitHubRepositoryService.SubmitPullRequestReviewResult;
import com.aiadvent.mcp.backend.github.GitHubRepositoryService.TeamInfo;
import com.aiadvent.mcp.backend.github.GitHubRepositoryService.TreeEntry;
import com.aiadvent.mcp.backend.github.GitHubRepositoryService.TreeEntryType;
import com.aiadvent.mcp.backend.github.GitHubRepositoryService.UserInfo;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
class GitHubTools {

  private final GitHubRepositoryService repositoryService;

  GitHubTools(GitHubRepositoryService repositoryService) {
    this.repositoryService = Objects.requireNonNull(repositoryService, "repositoryService");
  }

  @Tool(
      name = "github.list_repository_tree",
      description =
          "Возвращает структуру репозитория GitHub по ссылке owner/name и ветке/коммите."
              + " Поддерживает фильтрацию по path и ограничение глубины дерева.")
  GitHubTreeResponse listRepositoryTree(GitHubTreeRequest request) {
    RepositoryInput repositoryInput = requireRepository(request);
    ListRepositoryTreeInput serviceInput =
        new ListRepositoryTreeInput(
            new RepositoryRef(
                repositoryInput.owner(), repositoryInput.name(), repositoryInput.ref()),
            request.path(),
            request.recursive(),
            request.maxDepth(),
            request.maxEntries());

    ListRepositoryTreeResult result = repositoryService.listRepositoryTree(serviceInput);
    return new GitHubTreeResponse(
        toRepositoryInfo(result.repository()),
        result.resolvedRef(),
        result.truncated(),
        result.entries().stream().map(this::toTreeNode).toList());
  }

  @Tool(
      name = "github.list_pull_requests",
      description =
          "Возвращает список pull request'ов репозитория. Поддерживаются фильтры state/base/head и сортировка.")
  GitHubPullRequestsResponse listPullRequests(GitHubListPullRequestsRequest request) {
    RepositoryInput repositoryInput = requireRepository(request);
    ListPullRequestsResult result =
        repositoryService.listPullRequests(
            new ListPullRequestsInput(
                new RepositoryRef(
                    repositoryInput.owner(), repositoryInput.name(), repositoryInput.ref()),
                request.state(),
                request.head(),
                request.base(),
                request.limit(),
                request.sort(),
                request.direction()));
    return new GitHubPullRequestsResponse(
        toRepositoryInfo(result.repository()),
        result.pullRequests(),
        result.truncated());
  }

  @Tool(
      name = "github.get_pull_request",
      description = "Возвращает детальную информацию о pull request." )
  GitHubPullRequestDetailsResponse getPullRequest(GitHubGetPullRequestRequest request) {
    RepositoryInput repositoryInput = requireRepository(request);
    int number = requirePullRequestNumber(request.number());
    PullRequestDetailsResult result =
        repositoryService.getPullRequest(
            new GetPullRequestInput(
                new RepositoryRef(
                    repositoryInput.owner(), repositoryInput.name(), repositoryInput.ref()),
                number));
    return new GitHubPullRequestDetailsResponse(
        toRepositoryInfo(result.repository()),
        result.pullRequest());
  }

  @Tool(
      name = "github.get_pull_request_diff",
      description = "Возвращает unified diff для указанного pull request." )
  GitHubPullRequestDiffResponse getPullRequestDiff(GitHubGetPullRequestDiffRequest request) {
    RepositoryInput repositoryInput = requireRepository(request);
    int number = requirePullRequestNumber(request.number());
    PullRequestDiffResult result =
        repositoryService.getPullRequestDiff(
            new GetPullRequestDiffInput(
                new RepositoryRef(
                    repositoryInput.owner(), repositoryInput.name(), repositoryInput.ref()),
                number,
                request.maxBytes()));
    return new GitHubPullRequestDiffResponse(
        toRepositoryInfo(result.repository()),
        result.headSha(),
        result.diff(),
        result.truncated());
  }

  @Tool(
      name = "github.list_pull_request_comments",
      description =
          "Возвращает issue- и review-комментарии для pull request с ограничениями на количество.")
  GitHubPullRequestCommentsResponse listPullRequestComments(GitHubListPullRequestCommentsRequest request) {
    RepositoryInput repositoryInput = requireRepository(request);
    int number = requirePullRequestNumber(request.number());
    PullRequestCommentsResult result =
        repositoryService.listPullRequestComments(
            new ListPullRequestCommentsInput(
                new RepositoryRef(
                    repositoryInput.owner(), repositoryInput.name(), repositoryInput.ref()),
                number,
                request.issueCommentLimit(),
                request.reviewCommentLimit()));
    return new GitHubPullRequestCommentsResponse(
        toRepositoryInfo(result.repository()),
        result.issueComments(),
        result.reviewComments(),
        result.issueCommentsTruncated(),
        result.reviewCommentsTruncated());
  }

  @Tool(
      name = "github.list_pull_request_checks",
      description = "Возвращает статусы и check runs для head-коммита pull request.")
  GitHubPullRequestChecksResponse listPullRequestChecks(GitHubListPullRequestChecksRequest request) {
    RepositoryInput repositoryInput = requireRepository(request);
    int number = requirePullRequestNumber(request.number());
    PullRequestChecksResult result =
        repositoryService.listPullRequestChecks(
            new ListPullRequestChecksInput(
                new RepositoryRef(
                    repositoryInput.owner(), repositoryInput.name(), repositoryInput.ref()),
                number,
                request.checkRunLimit(),
                request.statusLimit()));
    return new GitHubPullRequestChecksResponse(
        toRepositoryInfo(result.repository()),
        result.pullRequestNumber(),
        result.headSha(),
        result.overallStatus(),
        result.checkRuns(),
        result.statuses(),
        result.checkRunsTruncated(),
        result.statusesTruncated());
  }

  @Tool(
      name = "github.create_pull_request_comment",
      description = "Публикует комментарий в pull request (issue-комментарий или review-комментарий).")
  GitHubPullRequestCommentResponse createPullRequestComment(GitHubCreatePullRequestCommentRequest request) {
    RepositoryInput repositoryInput = requireRepository(request);
    int number = requirePullRequestNumber(request.number());

    ReviewCommentLocation location =
        request.location() == null
            ? null
            : new ReviewCommentLocation(
                request.location().commitSha(),
                request.location().path(),
                request.location().line(),
                request.location().startLine(),
                request.location().endLine(),
                request.location().position());

    CreatePullRequestCommentResult result =
        repositoryService.createPullRequestComment(
            new CreatePullRequestCommentInput(
                new RepositoryRef(
                    repositoryInput.owner(), repositoryInput.name(), repositoryInput.ref()),
                number,
                request.body(),
                location));

    return new GitHubPullRequestCommentResponse(
        toRepositoryInfo(result.repository()),
        result.pullRequestNumber(),
        result.type().name().toLowerCase(Locale.ROOT),
        result.commentId(),
        result.reviewId(),
        result.htmlUrl(),
        result.created());
  }

  @Tool(
      name = "github.create_pull_request_review",
      description = "Создаёт review для pull request с опциональными комментариями и действием (APPROVE/REQUEST_CHANGES/COMMENT/PENDING).")
  GitHubCreatePullRequestReviewResponse createPullRequestReview(GitHubCreatePullRequestReviewRequest request) {
    RepositoryInput repositoryInput = requireRepository(request);
    int number = requirePullRequestNumber(request.number());

    List<ReviewCommentDraft> drafts = toReviewDrafts(request.comments());

    CreatePullRequestReviewResult result =
        repositoryService.createPullRequestReview(
            new CreatePullRequestReviewInput(
                new RepositoryRef(
                    repositoryInput.owner(), repositoryInput.name(), repositoryInput.ref()),
                number,
                request.body(),
                request.commitId(),
                request.event(),
                drafts));

    return new GitHubCreatePullRequestReviewResponse(
        toRepositoryInfo(result.repository()),
        result.pullRequestNumber(),
        toReviewSummary(result.review()),
        result.created());
  }

  @Tool(
      name = "github.submit_pull_request_review",
      description = "Отправляет существующее review pull request с выбранным действием.")
  GitHubSubmitPullRequestReviewResponse submitPullRequestReview(GitHubSubmitPullRequestReviewRequest request) {
    RepositoryInput repositoryInput = requireRepository(request);
    int number = requirePullRequestNumber(request.number());

    SubmitPullRequestReviewResult result =
        repositoryService.submitPullRequestReview(
            new SubmitPullRequestReviewInput(
                new RepositoryRef(
                    repositoryInput.owner(), repositoryInput.name(), repositoryInput.ref()),
                number,
                request.reviewId(),
                request.body(),
                request.event()));

    return new GitHubSubmitPullRequestReviewResponse(
        toRepositoryInfo(result.repository()),
        result.pullRequestNumber(),
        toReviewSummary(result.review()));
  }

  @Tool(
      name = "github.read_file",
      description = "Читает файл из репозитория GitHub (ветка или конкретный commit).")
  GitHubFileResponse readFile(GitHubFileRequest request) {
    RepositoryInput repositoryInput = requireRepository(request);
    if (!StringUtils.hasText(request.path())) {
      throw new IllegalArgumentException("path must not be blank");
    }
    ReadFileInput serviceInput =
        new ReadFileInput(
            new RepositoryRef(
                repositoryInput.owner(), repositoryInput.name(), repositoryInput.ref()),
            request.path());
    ReadFileResult result = repositoryService.readFile(serviceInput);
    return new GitHubFileResponse(
        toRepositoryInfo(result.repository()),
        result.resolvedRef(),
        toFileContent(result.file()),
        Instant.now());
  }

  private RepositoryInfo toRepositoryInfo(RepositoryRef ref) {
    return new RepositoryInfo(ref.owner(), ref.name(), ref.ref());
  }

  private TreeNode toTreeNode(TreeEntry entry) {
    return new TreeNode(entry.path(), entry.type().name(), entry.sha(), entry.size());
  }

  private FileContent toFileContent(RepositoryFile file) {
    return new FileContent(
        file.path(),
        file.sha(),
        file.size(),
        file.encoding(),
        file.downloadUrl(),
        file.contentBase64(),
        file.textContent());
  }

  private RepositoryInput requireRepository(RepositoryRequest request) {
    if (request == null || request.repository() == null) {
      throw new IllegalArgumentException("repository field must be provided");
    }
    return request.repository();
  }

  private int requirePullRequestNumber(Integer number) {
    if (number == null || number <= 0) {
      throw new IllegalArgumentException("pullRequestNumber must be positive");
    }
    return number;
  }

  private List<ReviewCommentDraft> toReviewDrafts(List<GitHubReviewCommentDraft> drafts) {
    if (drafts == null || drafts.isEmpty()) {
      return List.of();
    }
    List<ReviewCommentDraft> result = new ArrayList<>(drafts.size());
    for (GitHubReviewCommentDraft draft : drafts) {
      if (draft == null) {
        continue;
      }
      result.add(
          new ReviewCommentDraft(
              draft.body(),
              draft.path(),
              draft.line(),
              draft.startLine(),
              draft.endLine(),
              draft.position()));
    }
    return List.copyOf(result);
  }

  private GitHubPullRequestReview toReviewSummary(PullRequestReviewInfo info) {
    if (info == null) {
      return null;
    }
    return new GitHubPullRequestReview(info.id(), info.state(), info.htmlUrl(), info.submittedAt());
  }

  record RepositoryInfo(String owner, String name, String ref) {}

  interface RepositoryRequest {
    RepositoryInput repository();
  }

  record RepositoryInput(String owner, String name, String ref) {}

  record GitHubTreeRequest(
      RepositoryInput repository, String path, Boolean recursive, Integer maxDepth, Integer maxEntries)
      implements RepositoryRequest {}

  record GitHubTreeResponse(
      RepositoryInfo repository, String resolvedRef, boolean truncated, List<TreeNode> entries) {}

  record TreeNode(String path, String type, String sha, long size) {}

  record GitHubFileRequest(RepositoryInput repository, String path) implements RepositoryRequest {}

  record GitHubFileResponse(
      RepositoryInfo repository, String resolvedRef, FileContent file, Instant fetchedAt) {}

  record FileContent(
      String path,
      String sha,
      long size,
      String encoding,
      String downloadUrl,
      String contentBase64,
      String textContent) {}

  record GitHubListPullRequestsRequest(
      RepositoryInput repository, String state, String head, String base, Integer limit, String sort, String direction)
      implements RepositoryRequest {}

  record GitHubPullRequestsResponse(
      RepositoryInfo repository, List<PullRequestSummary> pullRequests, boolean truncated) {}

  record GitHubGetPullRequestRequest(RepositoryInput repository, Integer number) implements RepositoryRequest {}

  record GitHubPullRequestDetailsResponse(RepositoryInfo repository, PullRequestDetails pullRequest) {}

  record GitHubGetPullRequestDiffRequest(RepositoryInput repository, Integer number, Long maxBytes)
      implements RepositoryRequest {}

  record GitHubPullRequestDiffResponse(
      RepositoryInfo repository, String headSha, String diff, boolean truncated) {}

  record GitHubListPullRequestCommentsRequest(
      RepositoryInput repository, Integer number, Integer issueCommentLimit, Integer reviewCommentLimit)
      implements RepositoryRequest {}

  record GitHubPullRequestCommentsResponse(
      RepositoryInfo repository,
      List<PullRequestIssueComment> issueComments,
      List<PullRequestReviewComment> reviewComments,
      boolean issueCommentsTruncated,
      boolean reviewCommentsTruncated) {}

  record GitHubListPullRequestChecksRequest(
      RepositoryInput repository, Integer number, Integer checkRunLimit, Integer statusLimit)
      implements RepositoryRequest {}

  record GitHubPullRequestChecksResponse(
      RepositoryInfo repository,
      int pullRequestNumber,
      String headSha,
      String overallStatus,
      List<CheckRunInfo> checkRuns,
      List<CommitStatusInfo> statuses,
      boolean checkRunsTruncated,
      boolean statusesTruncated) {}

  record GitHubCreatePullRequestCommentRequest(
      RepositoryInput repository,
      Integer number,
      String body,
      GitHubReviewCommentLocation location)
      implements RepositoryRequest {}

  record GitHubReviewCommentLocation(
      String commitSha, String path, Integer line, Integer startLine, Integer endLine, Integer position) {}

  record GitHubPullRequestCommentResponse(
      RepositoryInfo repository,
      int pullRequestNumber,
      String type,
      long commentId,
      Long reviewId,
      String htmlUrl,
      boolean created) {}

  record GitHubCreatePullRequestReviewRequest(
      RepositoryInput repository,
      Integer number,
      String body,
      String commitId,
      String event,
      List<GitHubReviewCommentDraft> comments)
      implements RepositoryRequest {}

  record GitHubReviewCommentDraft(
      String body, String path, Integer line, Integer startLine, Integer endLine, Integer position) {}

  record GitHubCreatePullRequestReviewResponse(
      RepositoryInfo repository,
      int pullRequestNumber,
      GitHubPullRequestReview review,
      boolean created) {}

  record GitHubPullRequestReview(Long id, String state, String htmlUrl, Instant submittedAt) {}

  record GitHubSubmitPullRequestReviewRequest(
      RepositoryInput repository, Integer number, Long reviewId, String body, String event)
      implements RepositoryRequest {}

  record GitHubSubmitPullRequestReviewResponse(
      RepositoryInfo repository,
      int pullRequestNumber,
      GitHubPullRequestReview review) {}
}
