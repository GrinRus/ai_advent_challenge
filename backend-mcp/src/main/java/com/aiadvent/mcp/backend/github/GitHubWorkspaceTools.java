package com.aiadvent.mcp.backend.github;

import com.aiadvent.mcp.backend.github.GitHubRepositoryService.CheckoutStrategy;
import com.aiadvent.mcp.backend.github.GitHubRepositoryService.CommitAuthor;
import com.aiadvent.mcp.backend.github.GitHubRepositoryService.CommitFileChange;
import com.aiadvent.mcp.backend.github.GitHubRepositoryService.CommitWorkspaceDiffInput;
import com.aiadvent.mcp.backend.github.GitHubRepositoryService.CommitWorkspaceDiffResult;
import com.aiadvent.mcp.backend.github.GitHubRepositoryService.CreateBranchInput;
import com.aiadvent.mcp.backend.github.GitHubRepositoryService.CreateBranchResult;
import com.aiadvent.mcp.backend.github.GitHubRepositoryService.FetchRepositoryResult;
import com.aiadvent.mcp.backend.github.GitHubRepositoryService.GitFetchOptions;
import com.aiadvent.mcp.backend.github.GitHubRepositoryService.PushBranchInput;
import com.aiadvent.mcp.backend.github.GitHubRepositoryService.PushBranchResult;
import com.aiadvent.mcp.backend.github.GitHubRepositoryService.RepositoryRef;
import com.aiadvent.mcp.backend.github.workspace.WorkspaceInspectorService;
import com.aiadvent.mcp.backend.github.workspace.WorkspaceInspectorService.InspectWorkspaceRequest;
import com.aiadvent.mcp.backend.github.workspace.WorkspaceInspectorService.InspectWorkspaceResult;
import com.aiadvent.mcp.backend.github.workspace.WorkspaceInspectorService.WorkspaceItem;
import com.aiadvent.mcp.backend.github.workspace.WorkspaceInspectorService.WorkspaceItemType;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
class GitHubWorkspaceTools {

  private final WorkspaceAccessService accessService;

  GitHubWorkspaceTools(WorkspaceAccessService accessService) {
    this.accessService = Objects.requireNonNull(accessService, "accessService");
  }

  @Tool(
      name = "github.repository_fetch",
      description =
          "Скачивает репозиторий в изолированное workspace-хранилище. Принимает repository{owner,name,ref?}, "
              + "необязательный requestId для трассировки и параметры options: strategy (ARCHIVE_ONLY|ARCHIVE_WITH_FALLBACK_CLONE|CLONE_WITH_SUBMODULES), "
              + "shallowClone (по умолчанию true), includeSubmodules, cloneTimeout/ archiveTimeout (Duration ISO-8601), "
              + "archiveSizeLimit (байты) и detectKeyFiles. Возвращает workspaceId, абсолютный путь, "
              + "resolvedRef, commitSha, размеры скачивания и список ключевых файлов.")
  GitHubRepositoryFetchResponse fetchRepository(GitHubRepositoryFetchRequest request) {
    GitHubTools.RepositoryInput repositoryInput = requireRepository(request);
    GitFetchOptions options = toFetchOptions(request.options());
    FetchRepositoryResult result =
        accessService.fetchRepository(
            options,
            new RepositoryRef(
                repositoryInput.owner(), repositoryInput.name(), repositoryInput.ref()),
            request.requestId());
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
      name = "github.create_branch",
      description =
          "Создаёт новую ветку в репозитории и локальном workspace. Требует workspaceId и branchName. "
              + "sourceSha опционален (по умолчанию текущий HEAD workspace). Выполняется только после ручного подтверждения.")
  GitHubCreateBranchResponse createBranch(GitHubCreateBranchRequest request) {
    validateWorkspaceRequest(request.workspaceId());
    GitHubTools.RepositoryInput repositoryInput = requireRepository(request);
    CreateBranchResult result =
        accessService.createBranch(
            new CreateBranchInput(
                new RepositoryRef(
                    repositoryInput.owner(), repositoryInput.name(), repositoryInput.ref()),
                request.workspaceId(),
                request.branchName(),
                request.sourceSha()));

    return new GitHubCreateBranchResponse(
        toRepositoryInfo(result.repository()),
        result.workspaceId(),
        result.branchName(),
        result.branchRef(),
        result.sourceSha(),
        result.localHeadSha(),
        result.createdAt());
  }

  @Tool(
      name = "github.commit_workspace_diff",
      description =
          "Формирует commit из изменений в workspace. Требует branchName, commitMessage и автора (name/email). "
              + "Отклоняет пустой diff и превышение лимитов. Изменения остаются локальными до push.")
  GitHubCommitWorkspaceDiffResponse commitWorkspaceDiff(GitHubCommitWorkspaceDiffRequest request) {
    validateWorkspaceRequest(request.workspaceId());
    if (!StringUtils.hasText(request.branchName())) {
      throw new IllegalArgumentException("branchName must not be blank");
    }
    if (request.author() == null) {
      throw new IllegalArgumentException("author block is required");
    }
    if (!StringUtils.hasText(request.author().name()) || !StringUtils.hasText(request.author().email())) {
      throw new IllegalArgumentException("author.name and author.email must not be blank");
    }
    CommitWorkspaceDiffResult result =
        accessService.commitWorkspaceDiff(
            new CommitWorkspaceDiffInput(
                request.workspaceId(),
                request.branchName(),
                new CommitAuthor(request.author().name(), request.author().email()),
                request.commitMessage()));

    List<CommitFileChangeEntry> files =
        result.files().stream().map(this::toCommitFileChangeEntry).toList();

    return new GitHubCommitWorkspaceDiffResponse(
        toRepositoryInfo(result.repository()),
        result.workspaceId(),
        result.branchName(),
        result.commitSha(),
        result.filesChanged(),
        result.additions(),
        result.deletions(),
        files,
        result.committedAt());
  }

  @Tool(
      name = "github.push_branch",
      description =
          "Публикует локальные commits в удалённую ветку. Force push запрещён. Требует workspaceId и branchName.")
  GitHubPushBranchResponse pushBranch(GitHubPushBranchRequest request) {
    validateWorkspaceRequest(request.workspaceId());
    GitHubTools.RepositoryInput repositoryInput = requireRepository(request);
    PushBranchResult result =
        accessService.pushBranch(
            new PushBranchInput(
                new RepositoryRef(
                    repositoryInput.owner(), repositoryInput.name(), repositoryInput.ref()),
                request.workspaceId(),
                request.branchName(),
                request.force()));

    return new GitHubPushBranchResponse(
        toRepositoryInfo(result.repository()),
        result.workspaceId(),
        result.branchName(),
        result.localHeadSha(),
        result.remoteHeadSha(),
        result.commitsPushed(),
        result.pushedAt());
  }

  @Tool(
      name = "github.workspace_directory_inspector",
      description =
          "Инспектирует workspace, созданный GitHub fetch-инструментом. Требует workspaceId."
              + " Поддерживает фильтры includeGlobs/excludeGlobs (glob-паттерны), maxDepth (по умолчанию 4),"
              + " maxResults (по умолчанию 400, верхний предел 2000), includeTypes ([FILE,DIRECTORY]),"
              + " флаг includeHidden и detectProjects. Возвращает список элементов с типом, размером,"
              + " признаками проекта (Gradle/Maven/NPM), наличием gradlew, а также рекомендации по projectPath.")
  WorkspaceDirectoryInspectorResponse inspectWorkspace(
      WorkspaceDirectoryInspectorRequest request) {
    if (request == null || !StringUtils.hasText(request.workspaceId())) {
      throw new IllegalArgumentException("workspaceId must not be blank");
    }
    EnumSet<WorkspaceItemType> types = accessService.resolveTypes(request.includeTypes());
    InspectWorkspaceResult result =
        accessService.inspectWorkspace(
            new InspectWorkspaceRequest(
                request.workspaceId(),
                request.includeGlobs(),
                request.excludeGlobs(),
                request.maxDepth(),
                request.maxResults(),
                types,
                request.includeHidden(),
                request.detectProjects()));

    List<WorkspaceDirectoryEntry> entries =
        result.items().stream().map(this::toWorkspaceEntry).toList();

    return new WorkspaceDirectoryInspectorResponse(
        result.workspaceId(),
        result.workspacePath().toString(),
        entries,
        result.truncated(),
        result.warnings(),
        result.containsMultipleGradleProjects(),
        result.recommendedProjectPath(),
        result.totalMatches(),
        result.duration().toMillis(),
        result.inspectedAt());
  }

  @Tool(
      name = "workspace.read_file",
      description =
          "Читает файл из локального workspace, подготовленного GitHub MCP. Требует workspaceId и относительный путь. "
              + "maxBytes ограничивает размер ответа (по умолчанию 256 КБ, максимум 2 МБ). "
              + "Для бинарных файлов возвращается base64Content с признаком binary=true.")
  WorkspaceReadFileResponse readWorkspaceFile(WorkspaceReadFileRequest request) {
    validateRequest(request);
    WorkspaceAccessService.WorkspaceFilePayload payload =
        accessService.readWorkspaceFile(request.workspaceId(), request.path(), request.maxBytes());

    return new WorkspaceReadFileResponse(
        payload.workspaceId(),
        payload.path(),
        payload.sizeBytes(),
        payload.truncated(),
        payload.binary(),
        payload.encoding(),
        payload.content(),
        payload.base64Content(),
        payload.readAt());
  }

  private GitHubTools.RepositoryInfo toRepositoryInfo(RepositoryRef repository) {
    if (repository == null) {
      return null;
    }
    return new GitHubTools.RepositoryInfo(
        repository.owner(), repository.name(), repository.ref());
  }

  private GitHubTools.RepositoryInput requireRepository(
      GitHubTools.RepositoryRequest request) {
    GitHubTools.RepositoryInput repository = request.repository();
    if (repository == null) {
      throw new IllegalArgumentException("repository block is required");
    }
    if (!StringUtils.hasText(repository.owner()) || !StringUtils.hasText(repository.name())) {
      throw new IllegalArgumentException("repository.owner and repository.name must be provided");
    }
    return repository;
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

  private WorkspaceDirectoryEntry toWorkspaceEntry(WorkspaceItem item) {
    List<String> projectTypes = item.projectTypes();
    return new WorkspaceDirectoryEntry(
        item.path(),
        item.type().name().toLowerCase(Locale.ROOT),
        item.sizeBytes(),
        item.hidden(),
        item.symlink(),
        item.executable(),
        item.lastModified(),
        projectTypes,
        item.hasGradleWrapper());
  }

  private void validateRequest(WorkspaceReadFileRequest request) {
    if (request == null) {
      throw new IllegalArgumentException("request must not be null");
    }
    if (!StringUtils.hasText(request.workspaceId())) {
      throw new IllegalArgumentException("workspaceId must not be blank");
    }
    if (!StringUtils.hasText(request.path())) {
      throw new IllegalArgumentException("path must not be blank");
    }
  }

  private void validateWorkspaceRequest(String workspaceId) {
    if (!StringUtils.hasText(workspaceId)) {
      throw new IllegalArgumentException("workspaceId must not be blank");
    }
  }

  private CommitFileChangeEntry toCommitFileChangeEntry(CommitFileChange change) {
    if (change == null) {
      return null;
    }
    return new CommitFileChangeEntry(
        change.status(), change.path(), change.previousPath());
  }

  record GitHubRepositoryFetchRequest(
      GitHubTools.RepositoryInput repository,
      String requestId,
      GitHubRepositoryFetchOptions options)
      implements GitHubTools.RepositoryRequest {}

  record GitHubRepositoryFetchOptions(
      CheckoutStrategy strategy,
      Boolean shallowClone,
      Boolean includeSubmodules,
      Duration cloneTimeout,
      Duration archiveTimeout,
      Long archiveSizeLimit,
      Boolean detectKeyFiles) {}

  record GitHubRepositoryFetchResponse(
      GitHubTools.RepositoryInfo repository,
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

  record GitHubCreateBranchRequest(
      GitHubTools.RepositoryInput repository,
      String workspaceId,
      String branchName,
      String sourceSha)
      implements GitHubTools.RepositoryRequest {}

  record GitHubCreateBranchResponse(
      GitHubTools.RepositoryInfo repository,
      String workspaceId,
      String branchName,
      String branchRef,
      String sourceSha,
      String localHeadSha,
      Instant createdAt) {}

  record GitHubCommitWorkspaceDiffRequest(
      String workspaceId,
      String branchName,
      GitHubCommitAuthor author,
      String commitMessage) {}

  record GitHubCommitAuthor(String name, String email) {}

  record GitHubCommitWorkspaceDiffResponse(
      GitHubTools.RepositoryInfo repository,
      String workspaceId,
      String branchName,
      String commitSha,
      int filesChanged,
      int additions,
      int deletions,
      List<CommitFileChangeEntry> files,
      Instant committedAt) {}

  record CommitFileChangeEntry(String status, String path, String previousPath) {}

  record GitHubPushBranchRequest(
      GitHubTools.RepositoryInput repository,
      String workspaceId,
      String branchName,
      Boolean force)
      implements GitHubTools.RepositoryRequest {}

  record GitHubPushBranchResponse(
      GitHubTools.RepositoryInfo repository,
      String workspaceId,
      String branchName,
      String localHeadSha,
      String remoteHeadSha,
      int commitsPushed,
      Instant pushedAt) {}

  record WorkspaceReadFileRequest(String workspaceId, String path, Integer maxBytes) {}

  record WorkspaceReadFileResponse(
      String workspaceId,
      String path,
      long sizeBytes,
      boolean truncated,
      boolean binary,
      String encoding,
      String content,
      String base64Content,
      Instant readAt) {}

  private record FilePayload(boolean binary, String encoding, String content, String base64) {}
}
