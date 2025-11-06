package com.aiadvent.mcp.backend.github;

import com.aiadvent.mcp.backend.github.GitHubRepositoryService.FetchRepositoryInput;
import com.aiadvent.mcp.backend.github.GitHubRepositoryService.FetchRepositoryResult;
import com.aiadvent.mcp.backend.github.GitHubRepositoryService.GitFetchOptions;
import com.aiadvent.mcp.backend.github.GitHubRepositoryService.RepositoryRef;
import com.aiadvent.mcp.backend.github.workspace.TempWorkspaceService.Workspace;
import com.aiadvent.mcp.backend.github.workspace.WorkspaceInspectorService;
import com.aiadvent.mcp.backend.github.workspace.WorkspaceInspectorService.InspectWorkspaceRequest;
import com.aiadvent.mcp.backend.github.workspace.WorkspaceInspectorService.InspectWorkspaceResult;
import com.aiadvent.mcp.backend.github.workspace.WorkspaceInspectorService.WorkspaceItemType;
import com.aiadvent.mcp.backend.workspace.WorkspaceFileService;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class WorkspaceAccessService {

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
    return repositoryService.fetchRepository(
        new FetchRepositoryInput(repositoryRef, options, requestId));
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
}
