package com.aiadvent.mcp.backend.github.rag;

import com.aiadvent.mcp.backend.github.GitHubRepositoryFetchRegistry;
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
  private final GitHubRepositoryFetchRegistry fetchRegistry;

  public RepoRagTools(
      RepoRagStatusService statusService,
      RepoRagSearchService searchService,
      RepoRagNamespaceStateService namespaceStateService,
      GitHubRepositoryFetchRegistry fetchRegistry) {
    this.statusService = statusService;
    this.searchService = searchService;
    this.namespaceStateService = namespaceStateService;
    this.fetchRegistry = fetchRegistry;
  }

  @Tool(
      name = "repo.rag_index_status",
      description =
          """
          Следи за прогрессом GitHub RAG индексатора для конкретного `repoOwner`/`repoName`.
          • `status` показывает фазу (QUEUED, RUNNING, FAILED, READY).
          • `progress`, `etaSeconds`, `filesProcessed`, `chunksProcessed` помогают строить UI/alerts.
          • `lastError` и `attempt/maxAttempts` пригодятся, если очередь упала и нужно перезапускать fetch.
          • Флаг `ready=true` означает, что можно безопасно вызывать `repo.rag_search`/`repo.rag_search_simple`.

          Рекомендуемый сценарий: после `github.repository_fetch` периодически опрашивай инструмент,
          пока `ready` не станет true или не появится ошибка.
          """)
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
          """
          Основной инструмент поиска по GitHub RAG. Передавай `repoOwner`, `repoName` и `rawQuery`,
          остальные параметры управляют этапами pipeline:

          Retrieval
          • `topK`/`topKPerQuery` (1–40) контролируют количество документов.
          • `multiQuery` включает/настраивает генерацию подзапросов.
          • `filters`/`filterExpression` ограничивают язык и путь.

          Post-processing
          • `rerankTopN`, `codeAwareEnabled`, `codeAwareHeadMultiplier` управляют rerank’ом.
          • `neighborStrategy` ОБЯЗАТЕЛЬНО из OFF | LINEAR | PARENT_SYMBOL | CALL_GRAPH; подбирай его
            вместе с `neighborRadius` (0–5) и `neighborLimit` (≤12). Для простого сценария указывай
            LINEAR + radius/limit из бизнес-контекста. CALL_GRAPH доступен, только когда индекс содержит AST.
          • `maxContextTokens` срезает выдачу для генерации.

          Generation
          • `allowEmptyContext`, `useCompression`, `translateTo`, `generationLocale`,
            `instructionsTemplate` управляют финальным ответом.

          Возвращает чанки с метаданными, `augmentedPrompt`, финальные инструкции, признаки
          `contextMissing`/`noResults` и список `appliedModules`. Если параметр не требуется,
          передавай `null`, чтобы использовать конфиг сервера.
          """)
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
            input.codeAwareEnabled(),
            input.codeAwareHeadMultiplier(),
            input.neighborRadius(),
            input.neighborLimit(),
            input.neighborStrategy(),
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
          """
          Шорткат для свежего fetch: инструмент берёт пару owner/name из последнего
          `github.repository_fetch` (статус READY) и выполняет `repo.rag_search` с дефолтными
          настройками. Нужно указать только `rawQuery`.

          Используй, когда пользователь сразу после fetch задаёт вопросы про репозиторий, и неважны тонкие
          параметры. Если активного namespace нет или он ещё индексируется, вернётся понятная ошибка с
          подсказкой вызвать `github.repository_fetch` или `repo.rag_index_status`.
          """)
  public RepoRagSearchResponse ragSearchSimple(RepoRagSimpleSearchInput input) {
    if (!StringUtils.hasText(input.rawQuery())) {
      throw new IllegalArgumentException("rawQuery must not be blank");
    }
    com.aiadvent.mcp.backend.github.GitHubRepositoryFetchRegistry.LastFetchContext context =
        fetchRegistry
            .latest()
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Нет активного репозитория: выполните github.repository_fetch"));
    String repoOwner = normalize(context.repoOwner());
    String repoName = normalize(context.repoName());
    RepoRagNamespaceStateEntity state =
        namespaceStateService
            .findByRepoOwnerAndRepoName(repoOwner, repoName)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Репозиторий %s/%s ещё не индексировался — дождитесь завершения github.repository_fetch"
                            .formatted(context.repoOwner(), context.repoName())));
    if (!state.isReady()) {
      throw new IllegalStateException(
          "Репозиторий %s/%s ещё индексируется: проверьте repo.rag_index_status после github.repository_fetch"
              .formatted(context.repoOwner(), context.repoName()));
    }
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

  @Tool(
      name = "repo.rag_search_global",
      description =
          """
          Делает RAG-поиск сразу по всем `READY` namespace и возвращает те же поля, что `repo.rag_search`,
          добавляя в `matches[].metadata` фактический `repo_owner`/`repo_name` найденного документа. Полезно,
          когда пользователь не знает конкретный репозиторий или хочет разведку по каталогу.

          • Поддерживает те же параметры Retrieval/Post-processing: `topK`, `multiQuery`, `filters`,
            `neighborStrategy` (OFF | LINEAR | PARENT_SYMBOL | CALL_GRAPH), `maxContextTokens` и т.п.
          • Параметры `displayRepoOwner`/`displayRepoName` позволяют задать, как подписывать выдачу в UI
            (например, «mixed results for {org}»), если нужно отличать фактический репо от выбранного фильтра.
          • Остальные поля (`allowEmptyContext`, `useCompression`, `generationLocale`, `instructionsTemplate`)
            управляют генерацией ответа аналогично `repo.rag_search`.
          """)
  public RepoRagSearchResponse ragSearchGlobal(RepoRagGlobalSearchInput input) {
    if (!StringUtils.hasText(input.rawQuery())) {
      throw new IllegalArgumentException("rawQuery must not be blank");
    }
    RepoRagSearchService.GlobalSearchCommand command =
        new RepoRagSearchService.GlobalSearchCommand(
            input.rawQuery(),
            input.topK(),
            input.topKPerQuery(),
            input.minScore(),
            input.minScoreByLanguage(),
            input.rerankTopN(),
            input.codeAwareEnabled(),
            input.codeAwareHeadMultiplier(),
            input.neighborRadius(),
            input.neighborLimit(),
            input.neighborStrategy(),
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
            input.instructionsTemplate(),
            input.displayRepoOwner(),
            input.displayRepoName());
    return toResponse(searchService.searchGlobal(command));
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

  private String normalize(String value) {
    return value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
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
      Boolean codeAwareEnabled,
      Double codeAwareHeadMultiplier,
      Integer neighborRadius,
      Integer neighborLimit,
      String neighborStrategy,
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

  public record RepoRagGlobalSearchInput(
      String rawQuery,
      Integer topK,
      Integer topKPerQuery,
      Double minScore,
      Map<String, Double> minScoreByLanguage,
      Integer rerankTopN,
      Boolean codeAwareEnabled,
      Double codeAwareHeadMultiplier,
      Integer neighborRadius,
      Integer neighborLimit,
      String neighborStrategy,
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
      String instructionsTemplate,
      String displayRepoOwner,
      String displayRepoName) {}

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
