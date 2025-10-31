package com.aiadvent.mcp.backend.github;

import com.aiadvent.mcp.backend.github.GitHubRepositoryService.CheckRunInfo;
import com.aiadvent.mcp.backend.github.GitHubRepositoryService.CheckoutStrategy;
import com.aiadvent.mcp.backend.github.GitHubRepositoryService.CommentType;
import com.aiadvent.mcp.backend.github.GitHubRepositoryService.CommitStatusInfo;
import com.aiadvent.mcp.backend.github.GitHubRepositoryService.CreatePullRequestCommentInput;
import com.aiadvent.mcp.backend.github.GitHubRepositoryService.CreatePullRequestCommentResult;
import com.aiadvent.mcp.backend.github.GitHubRepositoryService.CreatePullRequestReviewInput;
import com.aiadvent.mcp.backend.github.GitHubRepositoryService.CreatePullRequestReviewResult;
import com.aiadvent.mcp.backend.github.GitHubRepositoryService.FetchRepositoryInput;
import com.aiadvent.mcp.backend.github.GitHubRepositoryService.FetchRepositoryResult;
import com.aiadvent.mcp.backend.github.GitHubRepositoryService.GetPullRequestDiffInput;
import com.aiadvent.mcp.backend.github.GitHubRepositoryService.GetPullRequestInput;
import com.aiadvent.mcp.backend.github.GitHubRepositoryService.GitFetchOptions;
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
import com.aiadvent.mcp.backend.github.workspace.WorkspaceInspectorService;
import com.aiadvent.mcp.backend.github.workspace.WorkspaceInspectorService.WorkspaceItemType;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
class GitHubTools {

  private final GitHubRepositoryService repositoryService;
  private final WorkspaceInspectorService workspaceInspectorService;

  GitHubTools(
      GitHubRepositoryService repositoryService,
      WorkspaceInspectorService workspaceInspectorService) {
    this.repositoryService = Objects.requireNonNull(repositoryService, "repositoryService");
    this.workspaceInspectorService =
        Objects.requireNonNull(workspaceInspectorService, "workspaceInspectorService");
  }

  @Tool(
      name = "github.list_repository_tree",
      description =
          "Сканирует git-дерево репозитория. Передайте repository{owner,name,ref?} (ref по умолчанию heads/main). "
              + "path очищается от ведущих/замыкающих '/' и задаёт стартовый каталог (пусто = корневая). "
              + "recursive=true включает обход подкаталогов. maxDepth ограничивается диапазоном 1-10, "
              + "maxEntries обрезает количество элементов; truncated=true сигнализирует об отсечении. "
              + "resolvedRef содержит точный SHA коммита, чьё дерево было использовано.")
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
      name = "github_repository_fetch",
      description =
          "Скачивает репозиторий в изолированное workspace-хранилище. Принимает repository{owner,name,ref?}, "
              + "необязательный requestId для трассировки и параметры options: strategy (ARCHIVE_ONLY|ARCHIVE_WITH_FALLBACK_CLONE|CLONE_WITH_SUBMODULES), "
              + "shallowClone (по умолчанию true), includeSubmodules, cloneTimeout/ archiveTimeout (Duration ISO-8601), "
              + "archiveSizeLimit (байты) и detectKeyFiles. Возвращает workspaceId, абсолютный путь, "
              + "resolvedRef, commitSha, размеры скачивания и список ключевых файлов.")
  GitHubRepositoryFetchResponse fetchRepository(GitHubRepositoryFetchRequest request) {
    RepositoryInput repositoryInput = requireRepository(request);
    GitFetchOptions options = toFetchOptions(request.options());
    FetchRepositoryResult result =
        repositoryService.fetchRepository(
            new FetchRepositoryInput(
                new RepositoryRef(
                    repositoryInput.owner(), repositoryInput.name(), repositoryInput.ref()),
                options,
                request.requestId()));
    return new GitHubRepositoryFetchResponse(
        toRepositoryInfo(result.repository()),
        result.workspaceId(),
        result.workspacePath().toString(),
        result.resolvedRef(),
        result.commitSha(),
        result.downloadedBytes(),
        result.workspaceSizeBytes(),
        result.downloadDuration() != null ? result.downloadDuration().toMillis() : 0L,
        result.strategy().name().toLowerCase(Locale.ROOT),
        result.keyFiles(),
        result.fetchedAt());
  }

  @Tool(
      name = "workspace_directory_inspector",
      description =
          "Инспектирует workspace, созданный GitHub fetch-инструментом. Требует workspaceId."
              + " Поддерживает фильтры includeGlobs/excludeGlobs (glob-паттерны), maxDepth (по умолчанию 4),"
              + " maxResults (по умолчанию 400, верхний предел 2000), includeTypes ([FILE,DIRECTORY]),"
              + " флаг includeHidden и detectProjects. Возвращает список элементов с типом, размером,"
              + " признаками проекта (Gradle/Maven/NPM), наличием gradlew, а также рекомендации по projectPath.")
  WorkspaceDirectoryInspectorResponse inspectWorkspace(WorkspaceDirectoryInspectorRequest request) {
    if (request == null || !StringUtils.hasText(request.workspaceId())) {
      throw new IllegalArgumentException("workspaceId must not be blank");
    }
    EnumSet<WorkspaceItemType> types = resolveTypes(request.includeTypes());
    WorkspaceInspectorService.InspectWorkspaceResult result =
        workspaceInspectorService.inspectWorkspace(
            new WorkspaceInspectorService.InspectWorkspaceRequest(
                request.workspaceId(),
                request.includeGlobs(),
                request.excludeGlobs(),
                request.maxDepth(),
                request.maxResults(),
                types,
                request.includeHidden(),
                request.detectProjects()));

    return new WorkspaceDirectoryInspectorResponse(
        result.workspaceId(),
        result.workspacePath().toString(),
        result.items().stream().map(this::toWorkspaceEntry).toList(),
        result.truncated(),
        result.warnings(),
        result.containsMultipleGradleProjects(),
        result.recommendedProjectPath(),
        result.totalMatches(),
        result.duration().toMillis(),
        result.inspectedAt());
  }

  @Tool(
      name = "github.list_pull_requests",
      description =
          "Возвращает сводки pull request'ов. Обязателен блок repository. Необязательные фильтры: "
              + "state (OPEN/CLOSED/ALL), head, base, sort (CREATED/UPDATED/POPULARITY/LONG_RUNNING), "
              + "direction (ASC/DESC). limit ограничивается диапазоном 1-50; некорректные значения фильтров "
              + "игнорируются. truncated=true означает, что список был урезан по limit.")
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
          "Читает расширенные сведения о pull request: тело, лейблы, ассайни, запрошенные ревьюеры/команды, "
              + "milestone, mergeability, maintainerCanModify, mergeCommitSha. Требуются repository и положительный number.")
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
          "Возвращает unified diff по PR. number > 0 обязателен. maxBytes (по умолчанию конфигурация или 1 МиБ) "
              + "ограничивает размер UTF-8 текста; при превышении response.truncated=true. headSha указывает SHA головой ветки.")
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
          "Возвращает issue- и review-комментарии для PR. issueCommentLimit/reviewCommentLimit (0–200) управляют объёмом, "
              + "0 отключает соответствующий список. issueComments содержат id/body/author/url, reviewComments дополнительно включают "
              + "diffHunk, path, line/startLine/endLine, position, side. truncated флаги показывают, что лимит был достигнут.")
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
          "Возвращает check runs и commit statuses для head SHA PR. Параметры checkRunLimit/statusLimit (0–200) "
              + "определяют число элементов; truncation флаги сообщают об урезанных списках. overallStatus агрегирует результат проверок.")
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
      description =
          "Публикует комментарий в PR. body обязателен. Если location не задан, создаётся issue-комментарий. "
              + "Для review-комментария location.path обязателен и нужно задать либо line ( >0 ), либо интервал startLine/endLine "
              + "(оба >0, endLine >= startLine; если они равны, используется одиночная строка), либо position (diff offset >0). "
              + "commitSha опционален и уточняет контекст diff. Инструмент проверяет дубликаты (по body+координатам) перед созданием.")
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
          "Создаёт review. Обязательны repository и number. event может быть APPROVE/REQUEST_CHANGES/COMMENT/PENDING; "
              + "для COMMENT и REQUEST_CHANGES требуется непустой body. commitId задаёт SHA (опционально). "
              + "comments — список черновиков: каждый содержит body+path и либо line, либо startLine/endLine "
              + "(валидируются как для комментариев), либо position >0. Интервалы с equal start/end превращаются в single-line. "
              + "Перед созданием сервис ищет дубликат review с тем же состоянием и body.")
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
          "Отправляет ранее созданное review. reviewId и number обязаны быть положительными. "
              + "event должен быть APPROVE, REQUEST_CHANGES или COMMENT (PENDING запрещён); "
              + "для COMMENT/REQUEST_CHANGES требуется body. Ответ возвращает обновлённую сводку review.")
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
          "Читает файл репозитория. repository обязателен; path очищается от лишних '/' и должен быть непустым. "
              + "Ответ содержит resolvedRef, метаданные файла (sha, size, encoding, downloadUrl), "
              + "Base64 содержимое и UTF-8 текст (если декодирование удалось). Результаты кешируются в соответствии с настройками.")
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

  private GitFetchOptions toFetchOptions(GitHubRepositoryFetchOptions options) {
    if (options == null) {
      return new GitFetchOptions(null, null, null, null, null, null, null);
    }
    return new GitFetchOptions(
        options.strategy(),
        options.shallowClone(),
        options.includeSubmodules(),
        options.cloneTimeout(),
        options.archiveTimeout(),
        options.archiveSizeLimit(),
        options.detectKeyFiles());
  }

  private EnumSet<WorkspaceItemType> resolveTypes(List<String> includeTypes) {
    if (includeTypes == null || includeTypes.isEmpty()) {
      return null;
    }
    EnumSet<WorkspaceItemType> resolved = EnumSet.noneOf(WorkspaceItemType.class);
    for (String type : includeTypes) {
      if (!StringUtils.hasText(type)) {
        continue;
      }
      try {
        resolved.add(WorkspaceItemType.valueOf(type.trim().toUpperCase(Locale.ROOT)));
      } catch (IllegalArgumentException ex) {
        // ignore unknown type
      }
    }
    return resolved.isEmpty() ? null : resolved;
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

  private WorkspaceDirectoryEntry toWorkspaceEntry(
      WorkspaceInspectorService.WorkspaceItem item) {
    return new WorkspaceDirectoryEntry(
        item.path(),
        item.type().name().toLowerCase(Locale.ROOT),
        item.sizeBytes(),
        item.hidden(),
        item.symlink(),
        item.executable(),
        item.lastModified(),
        item.projectTypes(),
        item.hasGradleWrapper());
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

  record GitHubRepositoryFetchRequest(
      RepositoryInput repository, String requestId, GitHubRepositoryFetchOptions options)
      implements RepositoryRequest {}

  record GitHubRepositoryFetchOptions(
      CheckoutStrategy strategy,
      Boolean shallowClone,
      Boolean includeSubmodules,
      Duration cloneTimeout,
      Duration archiveTimeout,
      Long archiveSizeLimit,
      Boolean detectKeyFiles) {}

  record GitHubRepositoryFetchResponse(
      RepositoryInfo repository,
      String workspaceId,
      String workspacePath,
      String resolvedRef,
      String commitSha,
      long downloadedBytes,
      long workspaceSizeBytes,
      long downloadTimeMs,
      String strategy,
      List<String> keyFiles,
      Instant fetchedAt) {}

  record WorkspaceDirectoryInspectorRequest(
      String workspaceId,
      List<String> includeGlobs,
      List<String> excludeGlobs,
      Integer maxDepth,
      Integer maxResults,
      List<String> includeTypes,
      Boolean includeHidden,
      Boolean detectProjects) {}

  record WorkspaceDirectoryInspectorResponse(
      String workspaceId,
      String workspacePath,
      List<WorkspaceDirectoryEntry> items,
      boolean truncated,
      List<String> warnings,
      boolean containsMultipleGradleProjects,
      String recommendedProjectPath,
      int totalMatches,
      long durationMs,
      Instant inspectedAt) {}

  record WorkspaceDirectoryEntry(
      String path,
      String type,
      long sizeBytes,
      boolean hidden,
      boolean symlink,
      boolean executable,
      Instant lastModified,
      List<String> projectTypes,
      boolean hasGradleWrapper) {}

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
