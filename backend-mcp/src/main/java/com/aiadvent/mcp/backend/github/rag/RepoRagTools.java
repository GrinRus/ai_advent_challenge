package com.aiadvent.mcp.backend.github.rag;

import com.aiadvent.mcp.backend.github.rag.RepoRagStatusService.StatusView;
import java.util.List;
import java.util.Locale;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class RepoRagTools {

  private final RepoRagStatusService statusService;
  private final RepoRagSearchService searchService;

  public RepoRagTools(RepoRagStatusService statusService, RepoRagSearchService searchService) {
    this.statusService = statusService;
    this.searchService = searchService;
  }

  @Tool(
      name = "repo.rag_index_status",
      description =
          "Возвращает состояние асинхронного индексатора GitHub RAG по паре repoOwner/repoName.")
  public RepoRagIndexStatusResponse ragIndexStatus(RepoRagIndexStatusInput input) {
    validateRepoInput(input.repoOwner(), input.repoName());
    StatusView status = statusService.currentStatus(input.repoOwner(), input.repoName());
    return new RepoRagIndexStatusResponse(
        status.repoOwner(),
        status.repoName(),
        status.status(),
        status.attempt(),
        status.maxAttempts(),
        status.progress(),
        status.etaSeconds(),
        status.filesProcessed(),
        status.chunksProcessed(),
        status.queuedAt(),
        status.startedAt(),
        status.completedAt(),
        status.lastError());
  }

  @Tool(
      name = "repo.rag_search",
      description =
          "Выполняет similarity search по namespace `repo:<owner>/<name>` и возвращает релевантные чанки.")
  public RepoRagSearchResponse ragSearch(RepoRagSearchInput input) {
    validateRepoInput(input.repoOwner(), input.repoName());
    if (!StringUtils.hasText(input.query())) {
      throw new IllegalArgumentException("query must not be blank");
    }
    RepoRagSearchService.SearchCommand command =
        new RepoRagSearchService.SearchCommand(
            input.repoOwner(),
            input.repoName(),
            input.query(),
            input.topK(),
            input.minScore(),
            input.rerankTopN());
    RepoRagSearchService.SearchResponse serviceResponse = searchService.search(command);
    List<RepoRagSearchMatch> matches =
        serviceResponse.matches().stream()
            .map(
                match ->
                    new RepoRagSearchMatch(
                        match.path(),
                        match.snippet(),
                        match.summary(),
                        match.score(),
                        match.metadata()))
            .toList();
    return new RepoRagSearchResponse(matches, serviceResponse.rerankApplied());
  }

  private void validateRepoInput(String owner, String name) {
    if (!StringUtils.hasText(owner) || !StringUtils.hasText(name)) {
      throw new IllegalArgumentException("repoOwner and repoName must be provided");
    }
  }

  public record RepoRagIndexStatusInput(String repoOwner, String repoName) {}

  public record RepoRagIndexStatusResponse(
      String repoOwner,
      String repoName,
      String status,
      int attempt,
      int maxAttempts,
      double progress,
      Long etaSeconds,
      long filesProcessed,
      long chunksProcessed,
      java.time.Instant queuedAt,
      java.time.Instant startedAt,
      java.time.Instant completedAt,
      String lastError) {}

  public record RepoRagSearchInput(
      String repoOwner,
      String repoName,
      String query,
      Integer topK,
      Double minScore,
      Integer rerankTopN) {}

  public record RepoRagSearchMatch(
      String path, String snippet, String summary, double score, java.util.Map<String, Object> metadata) {}

  public record RepoRagSearchResponse(List<RepoRagSearchMatch> matches, boolean rerankApplied) {}
}
