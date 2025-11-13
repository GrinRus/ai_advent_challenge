package com.aiadvent.mcp.backend.github;

import com.aiadvent.mcp.backend.github.GitHubRepositoryService.ApprovePullRequestInput;
import com.aiadvent.mcp.backend.github.GitHubRepositoryService.ApprovePullRequestResult;
import com.aiadvent.mcp.backend.github.GitHubRepositoryService.CommitWorkspaceDiffInput;
import com.aiadvent.mcp.backend.github.GitHubRepositoryService.CommitWorkspaceDiffResult;
import com.aiadvent.mcp.backend.github.GitHubRepositoryService.CreateBranchInput;
import com.aiadvent.mcp.backend.github.GitHubRepositoryService.CreateBranchResult;
import com.aiadvent.mcp.backend.github.GitHubRepositoryService.FetchRepositoryInput;
import com.aiadvent.mcp.backend.github.GitHubRepositoryService.FetchRepositoryResult;
import com.aiadvent.mcp.backend.github.GitHubRepositoryService.GitFetchOptions;
import com.aiadvent.mcp.backend.github.GitHubRepositoryService.MergePullRequestInput;
import com.aiadvent.mcp.backend.github.GitHubRepositoryService.MergePullRequestResult;
import com.aiadvent.mcp.backend.github.GitHubRepositoryService.OpenPullRequestInput;
import com.aiadvent.mcp.backend.github.GitHubRepositoryService.OpenPullRequestResult;
import com.aiadvent.mcp.backend.github.GitHubRepositoryService.PushBranchInput;
import com.aiadvent.mcp.backend.github.GitHubRepositoryService.PushBranchResult;
import com.aiadvent.mcp.backend.github.GitHubRepositoryService.RepositoryRef;
import com.aiadvent.mcp.backend.github.workspace.TempWorkspaceService.Workspace;
import com.aiadvent.mcp.backend.github.workspace.WorkspaceInspectorService;
import com.aiadvent.mcp.backend.github.workspace.WorkspaceInspectorService.InspectWorkspaceRequest;
import com.aiadvent.mcp.backend.github.workspace.WorkspaceInspectorService.InspectWorkspaceResult;
import com.aiadvent.mcp.backend.github.workspace.WorkspaceInspectorService.WorkspaceItemType;
import com.aiadvent.mcp.backend.workspace.WorkspaceFileService;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class WorkspaceAccessService {

  private static final Logger log = LoggerFactory.getLogger(WorkspaceAccessService.class);

  private final GitHubRepositoryService repositoryService;
  private final WorkspaceInspectorService inspectorService;
  private final WorkspaceFileService workspaceFileService;

  public WorkspaceAccessService(
      GitHubRepositoryService repositoryService,
      WorkspaceInspectorService inspectorService,
      WorkspaceFileService workspaceFileService) {
    this.repositoryService = Objects.requireNonNull(repositoryService, "repositoryService");
    this.inspectorService = Objects.requireNonNull(inspectorService, "inspectorService");
    this.workspaceFileService = Objects.requireNonNull(workspaceFileService, "workspaceFileService");
  }

  public FetchRepositoryResult fetchRepository(
      GitFetchOptions options,
      RepositoryRef repositoryRef,
      String requestId) {
    String normalizedRequestId = normalizeRequestId(requestId);
    Instant startedAt = Instant.now();
    try {
      FetchRepositoryResult result =
          repositoryService.fetchRepository(
              new FetchRepositoryInput(repositoryRef, options, requestId));
      long durationMs =
          result.downloadDuration() != null
              ? result.downloadDuration().toMillis()
              : Duration.between(startedAt, Instant.now()).toMillis();
      log.info(
          "gradle_mcp.fetch.completed requestId={} repository={}/{} ref={} downloadedBytes={} workspaceBytes={} durationMs={}",
          normalizedRequestId,
          repositoryRef.owner(),
          repositoryRef.name(),
          repositoryRef.ref(),
          result.downloadedBytes(),
          result.workspaceSizeBytes(),
          durationMs);
      return result;
    } catch (RuntimeException exception) {
      log.warn(
          "gradle_mcp.fetch.failed requestId={} repository={}/{} ref={} message={}",
          normalizedRequestId,
          repositoryRef.owner(),
          repositoryRef.name(),
          repositoryRef.ref(),
          exception.getMessage());
      throw exception;
    }
  }

  public CreateBranchResult createBranch(CreateBranchInput input) {
    return repositoryService.createBranch(input);
  }

  public CommitWorkspaceDiffResult commitWorkspaceDiff(CommitWorkspaceDiffInput input) {
    return repositoryService.commitWorkspaceDiff(input);
  }

  public PushBranchResult pushBranch(PushBranchInput input) {
    return repositoryService.pushBranch(input);
  }

  public OpenPullRequestResult openPullRequest(OpenPullRequestInput input) {
    return repositoryService.openPullRequest(input);
  }

  public ApprovePullRequestResult approvePullRequest(ApprovePullRequestInput input) {
    return repositoryService.approvePullRequest(input);
  }

  public MergePullRequestResult mergePullRequest(MergePullRequestInput input) {
    return repositoryService.mergePullRequest(input);
  }

  public InspectWorkspaceResult inspectWorkspace(InspectWorkspaceRequest request) {
    return inspectorService.inspectWorkspace(request);
  }

  public Workspace lookupWorkspace(String workspaceId) {
    return workspaceFileService.lookupWorkspace(workspaceId);
  }

  public WorkspaceFilePayload readWorkspaceFile(String workspaceId, String path, Integer maxBytes) {
    com.aiadvent.mcp.backend.workspace.WorkspaceFileService.WorkspaceFilePayload payload =
        workspaceFileService.readWorkspaceFile(workspaceId, path, maxBytes);
    return new WorkspaceFilePayload(
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

  public EnumSet<WorkspaceInspectorService.WorkspaceItemType> resolveTypes(
      List<String> includeTypes) {
    if (includeTypes == null || includeTypes.isEmpty()) {
      return EnumSet.of(
          WorkspaceInspectorService.WorkspaceItemType.FILE,
          WorkspaceInspectorService.WorkspaceItemType.DIRECTORY);
    }
    EnumSet<WorkspaceInspectorService.WorkspaceItemType> resolved =
        EnumSet.noneOf(WorkspaceInspectorService.WorkspaceItemType.class);
    for (String raw : includeTypes) {
      if (!StringUtils.hasText(raw)) {
        continue;
      }
      try {
        resolved.add(
            WorkspaceInspectorService.WorkspaceItemType.valueOf(raw.trim().toUpperCase(Locale.ROOT)));
      } catch (IllegalArgumentException ex) {
        // ignore invalid values
      }
    }
    if (resolved.isEmpty()) {
      resolved.add(WorkspaceInspectorService.WorkspaceItemType.FILE);
      resolved.add(WorkspaceInspectorService.WorkspaceItemType.DIRECTORY);
    }
    return resolved;
  }

  public record WorkspaceFilePayload(
      String workspaceId,
      String path,
      long sizeBytes,
      boolean truncated,
      boolean binary,
      String encoding,
      String content,
      String base64Content,
      Instant readAt) {}

  private String normalizeRequestId(String requestId) {
    return requestId != null && !requestId.isBlank() ? requestId : "n/a";
  }
}
