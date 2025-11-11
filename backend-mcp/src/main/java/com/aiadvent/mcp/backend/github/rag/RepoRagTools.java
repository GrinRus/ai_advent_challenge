package com.aiadvent.mcp.backend.github.rag;

import com.aiadvent.mcp.backend.github.rag.RepoRagStatusService.StatusView;
import com.aiadvent.mcp.backend.github.rag.persistence.RepoRagNamespaceStateEntity;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class RepoRagTools {

  private final RepoRagStatusService statusService;
  private final RepoRagSearchService searchService;
  private final RepoRagNamespaceStateService namespaceStateService;

  public RepoRagTools(
      RepoRagStatusService statusService,
      RepoRagSearchService searchService,
      RepoRagNamespaceStateService namespaceStateService) {
    this.statusService = statusService;
    this.searchService = searchService;
    this.namespaceStateService = namespaceStateService;
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
        status.lastError(),
        status.filesSkipped(),
        status.sourceRef(),
        status.commitSha(),
        status.workspaceId(),
        status.ready());
  }

  @Tool(
      name = "repo.rag_search",
      description =
          "Выполняет similarity search по namespace `repo:<owner>/<name>` и возвращает релевантные чанки.")
  public RepoRagSearchResponse ragSearch(RepoRagSearchInput input) {
    validateRepoInput(input.repoOwner(), input.repoName());
    if (!StringUtils.hasText(input.rawQuery())) {
      throw new IllegalArgumentException("rawQuery must not be blank");
    }
    RepoRagSearchService.SearchCommand command =
        new RepoRagSearchService.SearchCommand(
            input.repoOwner(),
            input.repoName(),
            input.rawQuery(),
            input.topK(),
            input.topKPerQuery(),
            input.minScore(),
            input.minScoreByLanguage(),
            input.rerankTopN(),
            input.rerankStrategy(),
            input.filters(),
            input.filterExpression(),
            input.history(),
            input.previousAssistantReply(),
            input.allowEmptyContext(),
            input.useCompression(),
            input.translateTo(),
            input.multiQuery(),
            input.maxContextTokens(),
            input.generationLocale(),
            input.instructionsTemplate());
    RepoRagSearchService.SearchResponse response = searchService.search(command);
    return toResponse(response);
  }

  @Tool(
      name = "repo.rag_search_simple",
      description =
          "Быстрый RAG-поиск по последнему успешно проиндексированному репозиторию после `github.repository_fetch`. "
              + "На вход принимает только rawQuery.")
  public RepoRagSearchResponse ragSearchSimple(RepoRagSimpleSearchInput input) {
    if (!StringUtils.hasText(input.rawQuery())) {
      throw new IllegalArgumentException("rawQuery must not be blank");
    }
    RepoRagNamespaceStateEntity state =
        namespaceStateService
            .findLatestReady()
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Нет готового репозитория: выполните github.repository_fetch"));
    RepoRagSearchService.SearchCommand command =
        new RepoRagSearchService.SearchCommand(
            state.getRepoOwner(),
            state.getRepoName(),
            input.rawQuery(),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            List.of(),
            null,
            Boolean.TRUE,
            null,
            null,
            null,
            null,
            null,
            null);
    return toResponse(searchService.search(command));
  }

  private RepoRagSearchResponse toResponse(RepoRagSearchService.SearchResponse serviceResponse) {
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
    return new RepoRagSearchResponse(
        matches,
        serviceResponse.rerankApplied(),
        serviceResponse.augmentedPrompt(),
        serviceResponse.instructions(),
        serviceResponse.contextMissing(),
        serviceResponse.noResults(),
        serviceResponse.noResultsReason(),
        serviceResponse.appliedModules());
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
      String lastError,
      long filesSkipped,
      String sourceRef,
      String commitSha,
      String workspaceId,
      boolean ready) {}

  public record RepoRagSearchInput(
      String repoOwner,
      String repoName,
      String rawQuery,
      Integer topK,
      Integer topKPerQuery,
      Double minScore,
      Map<String, Double> minScoreByLanguage,
      Integer rerankTopN,
      String rerankStrategy,
      RepoRagSearchFilters filters,
      String filterExpression,
      List<RepoRagSearchConversationTurn> history,
      String previousAssistantReply,
      Boolean allowEmptyContext,
      Boolean useCompression,
      String translateTo,
      RepoRagMultiQueryOptions multiQuery,
      Integer maxContextTokens,
      String generationLocale,
      String instructionsTemplate) {}

  public record RepoRagSimpleSearchInput(String rawQuery) {}

  public record RepoRagSearchMatch(
      String path, String snippet, String summary, double score, java.util.Map<String, Object> metadata) {}

  public record RepoRagSearchResponse(
      List<RepoRagSearchMatch> matches,
      boolean rerankApplied,
      String augmentedPrompt,
      String instructions,
      boolean contextMissing,
      boolean noResults,
      String noResultsReason,
      List<String> appliedModules) {}
}
