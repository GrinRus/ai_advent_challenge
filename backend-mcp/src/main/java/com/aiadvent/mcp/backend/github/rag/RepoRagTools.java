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
  private final RepoRagToolInputSanitizer inputSanitizer;

  public RepoRagTools(
      RepoRagStatusService statusService,
      RepoRagSearchService searchService,
      RepoRagNamespaceStateService namespaceStateService,
      GitHubRepositoryFetchRegistry fetchRegistry,
      RepoRagToolInputSanitizer inputSanitizer) {
    this.statusService = statusService;
    this.searchService = searchService;
    this.namespaceStateService = namespaceStateService;
    this.fetchRegistry = fetchRegistry;
    this.inputSanitizer = inputSanitizer;
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
          Основной инструмент поиска по GitHub RAG.

          Обязательные аргументы:
          • `repoOwner` — владелец репозитория (строка, пример `"GrinRus"`).
          • `repoName` — имя репозитория (пример `"ai_advent_challenge"`).
          • `rawQuery` — текст запроса/вопроса пользователя.

          Retrieval (поиск):
          • `topK` / `topKPerQuery` — сколько документов вернуть (1–40). Если не переданы, берутся дефолты сервера.
          • `multiQuery` — объект `{enabled, queries, maxQueries}` для генерации подзапросов.
          • `filters.languages` — массив языков (например `["java","python"]`), приводится к lower-case.
          • `filters.pathGlobs` — массив glob-шаблонов (`["backend/**","docs/*.md"]`).
          • `filterExpression` — текстовое условие vector store (например `"repo_owner == 'grinrus'"`).
          • `minScore` / `minScoreByLanguage` — пороги сходства (0.1–0.99).

          Post-processing:
          • `rerankTopN`, `codeAwareEnabled`, `codeAwareHeadMultiplier` — управление code-aware rerank’ом.
          • `neighborStrategy` (OFF | LINEAR | PARENT_SYMBOL | CALL_GRAPH) + `neighborRadius` (0–5) + `neighborLimit` (≤12) —
            логика расширения контекста соседями.
          • `maxContextTokens` — лимит токенов после post-processing (≥256).

          Generation:
          • `allowEmptyContext` — разрешить пустой контекст (true/false).
          • `useCompression` — включить Query Compression перед обращением к LLM.
          • `translateTo` — язык, на котором нужно получить финальный ответ (пример `"en"`).
          • `generationLocale` — локаль, используемая в шаблоне инструкций (например `"ru-RU"`).
          • `instructionsTemplate` — кастомный System Prompt.

          Выход: список `matches[]` (путь/сниппет/метаданные), `augmentedPrompt`, `instructions`,
          признаки `contextMissing` и `noResults`, причина `noResultsReason`, список этапов `appliedModules`,
          а также `warnings[]` с автокоррекциями входных параметров.

          Пример вызова:
          ```
          {
            "repoOwner": "GrinRus",
            "repoName": "ai_advent_challenge",
            "rawQuery": "Как устроен backend?",
            "topK": 12,
            "filters": {"languages": ["java"], "pathGlobs": ["backend/**"]},
            "neighborStrategy": "LINEAR",
            "neighborRadius": 1,
            "neighborLimit": 6,
            "allowEmptyContext": false,
            "generationLocale": "ru"
          }
          ```
          """)
  public RepoRagSearchResponse ragSearch(RepoRagSearchInput input) {
    RepoRagToolInputSanitizer.SanitizationResult<RepoRagSearchInput> sanitized =
        inputSanitizer.sanitizeSearch(input);
    RepoRagSearchInput normalized = sanitized.value();
    validateRepoInput(normalized.repoOwner(), normalized.repoName());
    if (!StringUtils.hasText(normalized.rawQuery())) {
      throw new IllegalArgumentException("rawQuery must not be blank");
    }
    RepoRagSearchService.SearchCommand command =
        new RepoRagSearchService.SearchCommand(
            normalized.repoOwner(),
            normalized.repoName(),
            normalized.rawQuery(),
            normalized.topK(),
            normalized.topKPerQuery(),
            normalized.minScore(),
            normalized.minScoreByLanguage(),
            normalized.rerankTopN(),
            normalized.rerankStrategy(),
            normalized.codeAwareEnabled(),
            normalized.codeAwareHeadMultiplier(),
            normalized.neighborRadius(),
            normalized.neighborLimit(),
            normalized.neighborStrategy(),
            normalized.filters(),
            normalized.filterExpression(),
            normalized.history(),
            normalized.previousAssistantReply(),
            normalized.allowEmptyContext(),
            normalized.useCompression(),
            normalized.translateTo(),
            normalized.multiQuery(),
            normalized.maxContextTokens(),
            normalized.generationLocale(),
            normalized.instructionsTemplate());
    RepoRagSearchService.SearchResponse response = searchService.search(command);
    return toResponse(response, sanitized.warnings());
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
    RepoRagToolInputSanitizer.SanitizationResult<RepoRagSimpleSearchInput> sanitized =
        inputSanitizer.sanitizeSimple(input);
    if (!StringUtils.hasText(sanitized.value().rawQuery())) {
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
            sanitized.value().rawQuery(),
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
    RepoRagSearchService.SearchResponse response = searchService.search(command);
    return toResponse(response, sanitized.warnings());
  }

  @Tool(
      name = "repo.rag_search_simple_global",
      description =
          """
          Быстрый глобальный поиск по всем READY namespace. Принимает только `rawQuery`, все остальные
          параметры — дефолтные (как у `repo.rag_search_global`). Для подсказок в UI использует пару
          owner/name из последнего `github.repository_fetch` (должен быть READY).
          """)
  public RepoRagSearchResponse ragSearchSimpleGlobal(RepoRagSimpleGlobalSearchInput input) {
    RepoRagToolInputSanitizer.SanitizationResult<RepoRagSimpleSearchInput> sanitized =
        inputSanitizer.sanitizeSimple(new RepoRagSimpleSearchInput(input.rawQuery()));
    if (!StringUtils.hasText(sanitized.value().rawQuery())) {
      throw new IllegalArgumentException("rawQuery must not be blank");
    }
    List<String> warnings = new java.util.ArrayList<>(sanitized.warnings());
    java.util.concurrent.atomic.AtomicReference<String> displayOwner = new java.util.concurrent.atomic.AtomicReference<>("global");
    java.util.concurrent.atomic.AtomicReference<String> displayName = new java.util.concurrent.atomic.AtomicReference<>("global");
    fetchRegistry
        .latest()
        .ifPresent(
            context -> {
              String normalizedOwner = normalize(context.repoOwner());
              String normalizedName = normalize(context.repoName());
              namespaceStateService
                  .findByRepoOwnerAndRepoName(normalizedOwner, normalizedName)
                  .filter(RepoRagNamespaceStateEntity::isReady)
                  .ifPresentOrElse(
                      state -> {
                        displayOwner.set(state.getRepoOwner());
                        displayName.set(state.getRepoName());
                      },
                      () ->
                          warnings.add(
                              "Последний github.repository_fetch ещё не READY — выдача будет с общим тегом global"));
            });
    RepoRagSearchService.GlobalSearchCommand command =
        new RepoRagSearchService.GlobalSearchCommand(
            sanitized.value().rawQuery(),
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
            null,
            displayOwner.get(),
            displayName.get());
    RepoRagSearchService.SearchResponse response = searchService.searchGlobal(command);
    return toResponse(response, warnings);
  }

  @Tool(
      name = "repo.rag_search_global",
      description =
          """
          Делает RAG-поиск по всем `READY` namespace.

          Обязательный аргумент:
          • `rawQuery` — текст запроса.

          Дополнительные параметры идентичны `repo.rag_search` (см. описание выше), за исключением
          отсутствия `repoOwner/repoName`. Обратите внимание:
          • `filters` и `filterExpression` применяются ко всем namespace (например, `language == 'python'`).
          • `displayRepoOwner` / `displayRepoName` — псевдонимы, которые покажет UI в ответе
            (пример: `"Mixed"` / `"catalog"`). В `matches[].metadata` всегда будут реальные `repo_owner`/`repo_name`.
          • `neighborStrategy`, `neighborRadius`, `neighborLimit`, `topK`, `multiQuery`, `maxContextTokens`,
            `allowEmptyContext`, `useCompression`, `generationLocale`, `instructionsTemplate` работают аналогично.

          Пример вызова:
          ```
          {
            "rawQuery": "policy engine implementation",
            "filters": {"languages": ["go"], "pathGlobs": ["**/policy/**"]},
            "topK": 15,
            "multiQuery": {"enabled": true, "queries": 4},
            "neighborStrategy": "CALL_GRAPH",
            "neighborRadius": 1,
            "neighborLimit": 4,
            "displayRepoOwner": "Mixed",
            "displayRepoName": "catalog"
          }
          ```
          """)
  public RepoRagSearchResponse ragSearchGlobal(RepoRagGlobalSearchInput input) {
    RepoRagToolInputSanitizer.SanitizationResult<RepoRagGlobalSearchInput> sanitized =
        inputSanitizer.sanitizeGlobal(input);
    if (!StringUtils.hasText(sanitized.value().rawQuery())) {
      throw new IllegalArgumentException("rawQuery must not be blank");
    }
    RepoRagSearchService.GlobalSearchCommand command =
        new RepoRagSearchService.GlobalSearchCommand(
            sanitized.value().rawQuery(),
            sanitized.value().topK(),
            sanitized.value().topKPerQuery(),
            sanitized.value().minScore(),
            sanitized.value().minScoreByLanguage(),
            sanitized.value().rerankTopN(),
            sanitized.value().codeAwareEnabled(),
            sanitized.value().codeAwareHeadMultiplier(),
            sanitized.value().neighborRadius(),
            sanitized.value().neighborLimit(),
            sanitized.value().neighborStrategy(),
            sanitized.value().filters(),
            sanitized.value().filterExpression(),
            sanitized.value().history(),
            sanitized.value().previousAssistantReply(),
            sanitized.value().allowEmptyContext(),
            sanitized.value().useCompression(),
            sanitized.value().translateTo(),
            sanitized.value().multiQuery(),
            sanitized.value().maxContextTokens(),
            sanitized.value().generationLocale(),
            sanitized.value().instructionsTemplate(),
            sanitized.value().displayRepoOwner(),
            sanitized.value().displayRepoName());
    return toResponse(searchService.searchGlobal(command), sanitized.warnings());
  }

  private RepoRagSearchResponse toResponse(
      RepoRagSearchService.SearchResponse serviceResponse, List<String> warnings) {
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
        serviceResponse.appliedModules(),
        warnings == null ? List.of() : List.copyOf(warnings));
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
      List<String> appliedModules,
      List<String> warnings) {}

  public record RepoRagSimpleGlobalSearchInput(String rawQuery) {}
}
