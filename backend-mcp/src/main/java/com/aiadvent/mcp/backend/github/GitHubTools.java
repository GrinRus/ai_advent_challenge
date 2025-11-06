package com.aiadvent.mcp.backend.github;

import com.aiadvent.mcp.backend.github.GitHubRepositoryService.ApprovePullRequestInput;
import com.aiadvent.mcp.backend.github.GitHubRepositoryService.ApprovePullRequestResult;
import com.aiadvent.mcp.backend.github.GitHubRepositoryService.CheckRunInfo;
import com.aiadvent.mcp.backend.github.GitHubRepositoryService.CommentType;
import com.aiadvent.mcp.backend.github.GitHubRepositoryService.CommitStatusInfo;
import com.aiadvent.mcp.backend.github.GitHubRepositoryService.CreatePullRequestCommentInput;
import com.aiadvent.mcp.backend.github.GitHubRepositoryService.CreatePullRequestCommentResult;
import com.aiadvent.mcp.backend.github.GitHubRepositoryService.CreatePullRequestReviewInput;
import com.aiadvent.mcp.backend.github.GitHubRepositoryService.CreatePullRequestReviewResult;
import com.aiadvent.mcp.backend.github.GitHubRepositoryService.MergeMethod;
import com.aiadvent.mcp.backend.github.GitHubRepositoryService.MergePullRequestInput;
import com.aiadvent.mcp.backend.github.GitHubRepositoryService.MergePullRequestResult;
import com.aiadvent.mcp.backend.github.GitHubRepositoryService.OpenPullRequestInput;
import com.aiadvent.mcp.backend.github.GitHubRepositoryService.OpenPullRequestResult;
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
import com.aiadvent.mcp.backend.github.GitHubRepositoryService.UserInfo;
import java.time.Duration;
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
          "Возвращает список файлов и директорий в репозитории. Тело запроса: "
              + "{\"repository\": {\"owner\": \"...\", \"name\": \"...\", \"ref\": \"refs/heads/main\"}, "
              + "\"path\": \"src\", \"recursive\": true, \"maxDepth\": 4, \"maxEntries\": 400}. "
              + "repository обязательно (owner/name, ref можно опустить — используется основная ветка). "
              + "path задаётся относительно корня; пустая строка означает весь репозиторий. recursive=true включает обход "
              + "подкаталогов. maxDepth допустим в диапазоне 1-10. maxEntries ограничивает число элементов; при превышении "
              + "результат помечается truncated=true. resolvedRef в ответе содержит точный SHA дерева.")
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
          "Выдаёт список PR c фильтрами и пагинацией. Пример запроса: "
              + "{\"repository\": {\"owner\": \"...\", \"name\": \"...\", \"ref\": \"refs/heads/main\"}, "
              + "\"state\": \"OPEN\", \"head\": \"user:branch\", \"base\": \"main\", \"limit\": 20, "
              + "\"sort\": \"UPDATED\", \"direction\": \"DESC\"}. "
              + "repository.owner/name обязательны; ref опционален (по умолчанию default branch). "
              + "state принимает OPEN/CLOSED/ALL. head и base — фильтры веток. limit (1-50) режет размер ответа. "
              + "sort: CREATED/UPDATED/POPULARITY/LONG_RUNNING, direction: ASC/DESC. "
              + "Если лимит достигнут, ответ содержит truncated=true.")
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
      description =
          "Возвращает детальную информацию о PR. Запрос формата {\"repository\": {\"owner\": \"...\", \"name\": \"...\"}, "
              + "\"number\": 123}. Поля repository и number (>0) обязательны. Ответ включает описание, лейблы, "
              + "assignees, requestedReviewers/Teams, milestone, mergeability, maintainerCanModify, mergeCommitSha и другую метаинформацию.")
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
      description =
          "Возвращает unified diff выбранного PR. Тело запроса: {\"repository\": {\"owner\": \"...\", \"name\": \"...\"}, "
              + "\"number\": 123, \"maxBytes\": 1048576}. number (>0) обязателен. maxBytes задаёт верхний предел на размер "
              + "UTF-8 diff (используется дефолт сервиса, если не указан). При обрезке diff поле truncated=true. "
              + "В ответе headSha указывает текущий SHA ветки."
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
          "Собирает issue- и review-комментарии для PR. Пример запроса: "
              + "{\"repository\": {\"owner\": \"...\", \"name\": \"...\"}, \"number\": 123, "
              + "\"issueCommentLimit\": 50, \"reviewCommentLimit\": 200}. number (>0) обязателен. "
              + "Лимиты 0-200; значение 0 отключает соответствующий список. В ответе issueComments содержат id/body/author/url, "
              + "reviewComments дополнительно дают diffHunk, path, line/startLine/endLine, position, side. "
              + "Флаги issueCommentsTruncated/reviewCommentsTruncated сигнализируют о срезе лимита.")
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
      description =
          "Возвращает check runs и commit statuses для head SHA PR. Запрос: "
              + "{\"repository\": {\"owner\": \"...\", \"name\": \"...\"}, \"number\": 123, "
              + "\"checkRunLimit\": 100, \"statusLimit\": 50}. number (>0) обязателен. "
              + "Лимиты 0-200 управляют объёмом; 0 означает отключить соответствующий список. "
              + "Ответ включает overallStatus, headSha, массивы checkRuns/statuses и флаги truncated для каждого массива.")
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
      name = "github.open_pull_request",
      description =
          "Создаёт PR в репозитории. Пример тела: "
              + "{\"repository\": {\"owner\": \"...\", \"name\": \"...\"}, \"headBranch\": \"feature\", "
              + "\"baseBranch\": \"main\", \"title\": \"Add feature\", \"body\": \"Описание\", "
              + "\"reviewers\": [\"user1\"], \"teamReviewers\": [\"team-alpha\"], \"draft\": false}. "
              + "repository.owner/name, headBranch, baseBranch и title обязательны. body опционален. "
              + "reviewers/teamReviewers — массивы логинов пользователей и команд. draft=true создаёт черновик.")
  GitHubOpenPullRequestResponse openPullRequest(GitHubOpenPullRequestRequest request) {
    RepositoryInput repositoryInput = requireRepository(request);
    OpenPullRequestResult result =
        repositoryService.openPullRequest(
            new OpenPullRequestInput(
                new RepositoryRef(
                    repositoryInput.owner(), repositoryInput.name(), repositoryInput.ref()),
                request.headBranch(),
                request.baseBranch(),
                request.title(),
                request.body(),
                request.reviewers(),
                request.teamReviewers(),
                request.draft()));

    return new GitHubOpenPullRequestResponse(
        toRepositoryInfo(result.repository()),
        result.pullRequestNumber(),
        result.htmlUrl(),
        result.headSha(),
        result.baseSha(),
        result.createdAt());
  }

  @Tool(
      name = "github.approve_pull_request",
      description =
          "Создаёт approve-review для PR. Тело: {\"repository\": {\"owner\": \"...\", \"name\": \"...\"}, "
              + "\"number\": 123, \"body\": \"LGTM\"}. repository и number (>0) обязательны. body опционален; "
              + "если не задан, создаётся пустой комментарий Approval.")
  GitHubApprovePullRequestResponse approvePullRequest(GitHubApprovePullRequestRequest request) {
    RepositoryInput repositoryInput = requireRepository(request);
    ApprovePullRequestResult result =
        repositoryService.approvePullRequest(
            new ApprovePullRequestInput(
                new RepositoryRef(
                    repositoryInput.owner(), repositoryInput.name(), repositoryInput.ref()),
                request.number(),
                request.body()));

    return new GitHubApprovePullRequestResponse(
        toRepositoryInfo(result.repository()),
        result.pullRequestNumber(),
        result.reviewId(),
        result.state(),
        result.submittedAt());
  }

  @Tool(
      name = "github.merge_pull_request",
      description =
          "Выполняет merge PR. Пример запроса: {\"repository\": {\"owner\": \"...\", \"name\": \"...\"}, "
              + "\"number\": 123, \"mergeMethod\": \"SQUASH\", \"commitTitle\": \"feat: change\", "
              + "\"commitMessage\": \"Описание\"}. repository и number (>0) обязательны. mergeMethod опционален "
              + "(MERGE|SQUASH|REBASE, по умолчанию MERGE). commitTitle/commitMessage используются при SQUASH/MERGE."
  GitHubMergePullRequestResponse mergePullRequest(GitHubMergePullRequestRequest request) {
    RepositoryInput repositoryInput = requireRepository(request);
    MergeMethod method = parseMergeMethod(request.mergeMethod());
    MergePullRequestResult result =
        repositoryService.mergePullRequest(
            new MergePullRequestInput(
                new RepositoryRef(
                    repositoryInput.owner(), repositoryInput.name(), repositoryInput.ref()),
                request.number(),
                method,
                request.commitTitle(),
                request.commitMessage()));

    return new GitHubMergePullRequestResponse(
        toRepositoryInfo(result.repository()),
        result.pullRequestNumber(),
        result.merged(),
        result.mergeSha(),
        result.message(),
        result.mergedAt());
  }

  @Tool(
      name = "github.create_pull_request_comment",
      description =
          "Публикует комментарий в PR. Пример: {\"repository\": {\"owner\": \"...\", \"name\": \"...\"}, "
              + "\"number\": 123, \"body\": \"Нужно поправить\", "
              + "\"location\": {\"path\": \"src/App.java\", \"line\": 42, \"commitSha\": \"abc\"}}. "
              + "repository, number (>0) и body обязательны. Если location отсутствует, создаётся issue-комментарий. "
              + "Для review-комментария требуется location.path и координаты: либо line (>0), либо диапазон startLine/endLine "
              + "(>0, endLine >= startLine), либо position (>0). commitSha уточняет контекст diff. Дубликаты (body+координаты) пропускаются."
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
      description =
          "Создаёт review с черновыми комментариями. Тело запроса: "
              + "{\"repository\": {\"owner\": \"...\", \"name\": \"...\"}, \"number\": 123, "
              + "\"event\": \"COMMENT\", \"body\": \"Общее замечание\", \"commitId\": \"abc\", "
              + "\"comments\": [{\"body\": \"Поправьте\", \"path\": \"src/App.java\", \"line\": 42}]}. "
              + "repository и number обязательны. event допускает APPROVE/REQUEST_CHANGES/COMMENT/PENDING. "
              + "Для COMMENT/REQUEST_CHANGES нужен непустой body. comments — массив драфтов: каждый требует body+path и координаты "
              + "(line или startLine/endLine или position). commitId опционален. Перед созданием выполняется поиск дубликатов review."
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
      description =
          "Завершает ранее созданный review. Запрос: {\"repository\": {\"owner\": \"...\", \"name\": \"...\"}, "
              + "\"number\": 123, \"reviewId\": 456, \"event\": \"APPROVE\", \"body\": \"OK\"}. "
              + "repository, number (>0) и reviewId (>0) обязательны. event допускает APPROVE/REQUEST_CHANGES/COMMENT; "
              + "для COMMENT и REQUEST_CHANGES требуется непустой body."
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
      description =
          "Читает blob из репозитория. Запрос: {\"repository\": {\"owner\": \"...\", \"name\": \"...\", \"ref\": \"refs/heads/main\"}, "
              + "\"path\": \"docs/README.md\"}. repository и path обязательны; path должен быть относительным и непустым. "
              + "Ответ возвращает resolvedRef (SHA коммита), метаданные (sha,size,encoding,downloadUrl) и содержимое "
              + "в base64 и текстовом виде (textContent доступен, если файл не бинарный)."
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

  private MergeMethod parseMergeMethod(String mergeMethod) {
    if (!StringUtils.hasText(mergeMethod)) {
      return MergeMethod.MERGE;
    }
    try {
      return MergeMethod.valueOf(mergeMethod.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      throw new IllegalArgumentException("Unsupported mergeMethod: " + mergeMethod);
    }
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

  record GitHubOpenPullRequestRequest(
      RepositoryInput repository,
      String headBranch,
      String baseBranch,
      String title,
      String body,
      List<String> reviewers,
      List<String> teamReviewers,
      Boolean draft)
      implements RepositoryRequest {}

  record GitHubOpenPullRequestResponse(
      RepositoryInfo repository,
      int pullRequestNumber,
      String htmlUrl,
      String headSha,
      String baseSha,
      Instant createdAt) {}

  record GitHubApprovePullRequestRequest(
      RepositoryInput repository, Integer number, String body) implements RepositoryRequest {}

  record GitHubApprovePullRequestResponse(
      RepositoryInfo repository,
      int pullRequestNumber,
      long reviewId,
      String state,
      Instant submittedAt) {}

  record GitHubMergePullRequestRequest(
      RepositoryInput repository,
      Integer number,
      String mergeMethod,
      String commitTitle,
      String commitMessage)
      implements RepositoryRequest {}

  record GitHubMergePullRequestResponse(
      RepositoryInfo repository,
      int pullRequestNumber,
      boolean merged,
      String mergeSha,
      String message,
      Instant mergedAt) {}
}
