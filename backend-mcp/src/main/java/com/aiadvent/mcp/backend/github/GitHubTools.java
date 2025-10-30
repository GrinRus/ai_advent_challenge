package com.aiadvent.mcp.backend.github;

import com.aiadvent.mcp.backend.github.GitHubRepositoryService.ListRepositoryTreeInput;
import com.aiadvent.mcp.backend.github.GitHubRepositoryService.ListRepositoryTreeResult;
import com.aiadvent.mcp.backend.github.GitHubRepositoryService.ReadFileInput;
import com.aiadvent.mcp.backend.github.GitHubRepositoryService.ReadFileResult;
import com.aiadvent.mcp.backend.github.GitHubRepositoryService.RepositoryFile;
import com.aiadvent.mcp.backend.github.GitHubRepositoryService.RepositoryRef;
import com.aiadvent.mcp.backend.github.GitHubRepositoryService.TreeEntry;
import com.aiadvent.mcp.backend.github.GitHubRepositoryService.TreeEntryType;
import java.time.Instant;
import java.util.List;
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
}
