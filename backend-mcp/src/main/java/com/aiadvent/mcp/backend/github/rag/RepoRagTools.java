package com.aiadvent.mcp.backend.github.rag;

import com.aiadvent.mcp.backend.config.GitHubRagProperties;
import com.aiadvent.mcp.backend.github.GitHubRepositoryFetchRegistry;
import com.aiadvent.mcp.backend.github.rag.RepoRagStatusService.StatusView;
import com.aiadvent.mcp.backend.github.rag.persistence.RepoRagNamespaceStateEntity;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
  private final GitHubRagProperties properties;
  private final RagParameterGuard parameterGuard;
  private final GraphQueryService graphQueryService;

  public RepoRagTools(
      RepoRagStatusService statusService,
      RepoRagSearchService searchService,
      RepoRagNamespaceStateService namespaceStateService,
      GitHubRepositoryFetchRegistry fetchRegistry,
      RepoRagToolInputSanitizer inputSanitizer,
      GitHubRagProperties properties,
      RagParameterGuard parameterGuard,
      @org.springframework.lang.Nullable GraphQueryService graphQueryService) {
    this.statusService = statusService;
    this.searchService = searchService;
    this.namespaceStateService = namespaceStateService;
    this.fetchRegistry = fetchRegistry;
    this.inputSanitizer = inputSanitizer;
    this.properties = properties;
    this.parameterGuard = parameterGuard;
    this.graphQueryService = graphQueryService;
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
        status.ready(),
        status.astReady(),
        status.astSchemaVersion(),
        status.astReadyAt(),
        status.graphReady(),
        status.graphSchemaVersion(),
        status.graphReadyAt());
  }

  @Tool(
      name = "repo.rag_search",
      description =
          """
          Основной инструмент GitHub RAG. Сервер сам управляет параметрами поиска через профили,
          поэтому клиенту нужно указать только:
          • `repoOwner` / `repoName` — репозиторий, по которому выполняем поиск.
          • `rawQuery` — текст вопроса.
          • `profile` — имя заранее настроенного пресета (`conservative`, `balanced`, `aggressive`).
          • `conversationContext` — история (`history[]`) и `previousAssistantReply`, если нужно учесть прошлый ответ.
          • `responseChannel` — управляет форматом ответа (`raw`, `summary`, `both`). По умолчанию MCP возвращает оба варианта: сырое сообщение (augmented prompt из vector store) и сжатое summary.

          Остальные параметры (topK, multiQuery, neighbor, code-aware, лимиты) подтягиваются из профиля,
          а результат всегда включает `appliedModules += "profile:<name>"`, чтобы оператор видел, какой сетап применился.
          Санитайзер сам подставит repoOwner/repoName из последнего READY namespace, если они опущены,
          и вернёт предупреждения в `warnings[]`.
          """)
  public RepoRagSearchResponse ragSearch(RepoRagSearchInput input) {
    RepoRagToolInputSanitizer.SanitizationResult<RepoRagSearchInput> sanitized =
        inputSanitizer.sanitizeSearch(input);
    RepoRagSearchInput normalized = sanitized.value();
    validateRepoInput(normalized.repoOwner(), normalized.repoName());
    if (!StringUtils.hasText(normalized.rawQuery())) {
      throw new IllegalArgumentException("rawQuery must not be blank");
    }
    GitHubRagProperties.ResolvedRagParameterProfile profile =
        properties.resolveProfile(normalized.profile());
    RagParameterGuard.GuardResult guardResult = parameterGuard.apply(profile);
    RepoRagResponseChannel responseChannel =
        RepoRagResponseChannel.fromToken(normalized.responseChannel());
    RepoRagSearchService.SearchCommand command =
      new RepoRagSearchService.SearchCommand(
          normalized.repoOwner(),
          normalized.repoName(),
          normalized.rawQuery(),
          guardResult.plan(),
          normalized.history(),
          normalized.previousAssistantReply(),
          responseChannel);
    RepoRagSearchService.SearchResponse response = searchService.search(command);
    return toResponse(
        response,
        mergeWarnings(sanitized.warnings(), guardResult.warnings()),
        List.of("profile:" + guardResult.plan().profileName()));
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
    GitHubRagProperties.ResolvedRagParameterProfile profile = properties.resolveProfile(null);
    RagParameterGuard.GuardResult guardResult = parameterGuard.apply(profile);
    RepoRagSearchService.SearchCommand command =
        new RepoRagSearchService.SearchCommand(
            state.getRepoOwner(),
            state.getRepoName(),
            sanitized.value().rawQuery(),
            guardResult.plan(),
            List.of(),
            null,
            RepoRagResponseChannel.BOTH);
    RepoRagSearchService.SearchResponse response = searchService.search(command);
    return toResponse(
        response,
        mergeWarnings(sanitized.warnings(), guardResult.warnings()),
        List.of("profile:" + guardResult.plan().profileName()));
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
    GitHubRagProperties.ResolvedRagParameterProfile profile = properties.resolveProfile(null);
    RagParameterGuard.GuardResult guardResult = parameterGuard.apply(profile);
    RepoRagSearchService.GlobalSearchCommand command =
        new RepoRagSearchService.GlobalSearchCommand(
            sanitized.value().rawQuery(),
            guardResult.plan(),
            List.of(),
            null,
            displayOwner.get(),
            displayName.get(),
            RepoRagResponseChannel.BOTH);
    RepoRagSearchService.SearchResponse response = searchService.searchGlobal(command);
    return toResponse(
        response,
        mergeWarnings(warnings, guardResult.warnings()),
        List.of("profile:" + guardResult.plan().profileName()));
  }

  @Tool(
      name = "repo.rag_search_global",
      description =
          """
          Глобальный вариант: ищет по всем READY namespace. Клиент передаёт `rawQuery`, `profile`,
          `conversationContext`, а подписи для UI (`displayRepoOwner/displayRepoName`) используются только
          для отображения ответа. Все параметры поиска подбираются в соответствии с профилем и фиксируются
          в `appliedModules`. Поле `responseChannel` работает так же, как у `repo.rag_search` и по умолчанию
          даёт и сырое, и summary-представление ответа.
          """)
  public RepoRagSearchResponse ragSearchGlobal(RepoRagGlobalSearchInput input) {
    RepoRagToolInputSanitizer.SanitizationResult<RepoRagGlobalSearchInput> sanitized =
        inputSanitizer.sanitizeGlobal(input);
    if (!StringUtils.hasText(sanitized.value().rawQuery())) {
      throw new IllegalArgumentException("rawQuery must not be blank");
    }
    GitHubRagProperties.ResolvedRagParameterProfile profile =
        properties.resolveProfile(sanitized.value().profile());
    RagParameterGuard.GuardResult guardResult = parameterGuard.apply(profile);
    RepoRagResponseChannel responseChannel =
        RepoRagResponseChannel.fromToken(sanitized.value().responseChannel());
    RepoRagSearchService.GlobalSearchCommand command =
        new RepoRagSearchService.GlobalSearchCommand(
            sanitized.value().rawQuery(),
            guardResult.plan(),
            sanitized.value().history(),
            sanitized.value().previousAssistantReply(),
            sanitized.value().displayRepoOwner(),
            sanitized.value().displayRepoName(),
            responseChannel);
    RepoRagSearchService.SearchResponse response = searchService.searchGlobal(command);
    return toResponse(
        response,
        mergeWarnings(sanitized.warnings(), guardResult.warnings()),
        List.of("profile:" + guardResult.plan().profileName()));
  }

  @Tool(
      name = "repo.code_graph_neighbors",
      description =
          """
          Возвращает соседей символа из Neo4j-графа. Полезно для IDE-подобной навигации.
          Вход: `namespace`, `symbolFqn`, `direction` (OUTGOING/INCOMING/BOTH), `relation` (CALLS/IMPLEMENTS/READS_FIELD/USES_TYPE), `limit`.
          Выход: `nodes[]` с файлами/линиями и `edges[]` c relation.
          """)
  public GraphNeighborsResponse codeGraphNeighbors(GraphNeighborsInput input) {
    ensureGraphEnabled();
    if (!StringUtils.hasText(input.namespace()) || !StringUtils.hasText(input.symbolFqn())) {
      throw new IllegalArgumentException("namespace and symbolFqn must not be blank");
    }
    GraphQueryService.Direction direction =
        Optional.ofNullable(input.direction())
            .map(GraphQueryService.Direction::valueOf)
            .orElse(GraphQueryService.Direction.OUTGOING);
    Set<String> relations = new HashSet<>();
    if (input.relation() != null) {
      relations.add(input.relation());
    }
    GraphQueryService.GraphNeighbors neighbors =
        graphQueryService.neighbors(input.namespace(), input.symbolFqn(), direction, relations, input.limit());
    return new GraphNeighborsResponse(neighbors.nodes(), neighbors.edges());
  }

  @Tool(
      name = "repo.code_graph_definition",
      description =
          """
          Возвращает определение символа из графа (файл, строки, kind, visibility).
          Вход: `namespace`, `symbolFqn`.
          """)
  public GraphDefinitionResponse codeGraphDefinition(GraphDefinitionInput input) {
    ensureGraphEnabled();
    if (!StringUtils.hasText(input.namespace()) || !StringUtils.hasText(input.symbolFqn())) {
      throw new IllegalArgumentException("namespace and symbolFqn must not be blank");
    }
    GraphQueryService.GraphNode node =
        graphQueryService.definition(input.namespace(), input.symbolFqn());
    return new GraphDefinitionResponse(node);
  }

  @Tool(
      name = "repo.code_graph_path",
      description =
          """
          Строит кратчайший путь между двумя символами (до maxDepth). Полезно для сценариев контроллер → сервис → репозиторий.
          Вход: `namespace`, `sourceFqn`, `targetFqn`, `relation` (опционально), `maxDepth` (по умолчанию 4).
          """)
  public GraphNeighborsResponse codeGraphPath(GraphPathInput input) {
    ensureGraphEnabled();
    if (!StringUtils.hasText(input.namespace())
        || !StringUtils.hasText(input.sourceFqn())
        || !StringUtils.hasText(input.targetFqn())) {
      throw new IllegalArgumentException("namespace, sourceFqn and targetFqn must not be blank");
    }
    Set<String> relations = new HashSet<>();
    if (input.relation() != null) {
      relations.add(input.relation());
    }
    GraphQueryService.GraphNeighbors path =
        graphQueryService.shortestPath(
            input.namespace(),
            input.sourceFqn(),
            input.targetFqn(),
            relations,
            input.maxDepth());
    return new GraphNeighborsResponse(path.nodes(), path.edges());
  }

  private List<String> mergeWarnings(List<String> first, List<String> second) {
    boolean emptyFirst = first == null || first.isEmpty();
    boolean emptySecond = second == null || second.isEmpty();
    if (emptyFirst && emptySecond) {
      return List.of();
    }
    if (emptyFirst) {
      return List.copyOf(second);
    }
    if (emptySecond) {
      return List.copyOf(first);
    }
    List<String> merged = new java.util.ArrayList<>(first.size() + second.size());
    merged.addAll(first);
    merged.addAll(second);
    return List.copyOf(merged);
  }

  private RepoRagSearchResponse toResponse(
      RepoRagSearchService.SearchResponse serviceResponse,
      List<String> warnings,
      List<String> extraModules) {
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
    List<String> appliedModules = new java.util.ArrayList<>(serviceResponse.appliedModules());
    if (extraModules != null && !extraModules.isEmpty()) {
      appliedModules.addAll(extraModules);
    }
    List<String> aggregatedWarnings = mergeWarnings(serviceResponse.warnings(), warnings);
    return new RepoRagSearchResponse(
        matches,
        serviceResponse.rerankApplied(),
        serviceResponse.augmentedPrompt(),
        serviceResponse.instructions(),
        serviceResponse.contextMissing(),
        serviceResponse.noResults(),
        serviceResponse.noResultsReason(),
        List.copyOf(appliedModules),
        aggregatedWarnings,
        serviceResponse.summary(),
        serviceResponse.rawAnswer());
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
      boolean ready,
      boolean astReady,
      int astSchemaVersion,
      java.time.Instant astReadyAt,
      boolean graphReady,
      int graphSchemaVersion,
      java.time.Instant graphReadyAt) {}

  @JsonIgnoreProperties(ignoreUnknown = false)
  public record RepoRagSearchInput(
      String repoOwner,
      String repoName,
      String rawQuery,
      String profile,
      List<RepoRagSearchConversationTurn> history,
      String previousAssistantReply,
      String responseChannel) {}

  public record RepoRagSimpleSearchInput(String rawQuery) {}

  @JsonIgnoreProperties(ignoreUnknown = false)
  public record RepoRagGlobalSearchInput(
      String rawQuery,
      String profile,
      List<RepoRagSearchConversationTurn> history,
      String previousAssistantReply,
      String displayRepoOwner,
      String displayRepoName,
      String responseChannel) {}

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
      List<String> warnings,
      String summary,
      String rawAnswer) {}

  public record RepoRagSimpleGlobalSearchInput(String rawQuery) {}

  private void ensureGraphEnabled() {
    if (graphQueryService == null || !properties.getGraph().isEnabled()) {
      throw new IllegalStateException("Neo4j graph is disabled. Set GITHUB_RAG_GRAPH_ENABLED=true.");
    }
  }

  public record GraphNeighborsInput(
      String namespace, String symbolFqn, String direction, String relation, int limit) {}

  public record GraphNeighborsResponse(
      List<GraphQueryService.GraphNode> nodes, List<GraphQueryService.GraphEdge> edges) {}

  public record GraphDefinitionInput(String namespace, String symbolFqn) {}

  public record GraphDefinitionResponse(GraphQueryService.GraphNode symbol) {}

  public record GraphPathInput(
      String namespace, String sourceFqn, String targetFqn, String relation, int maxDepth) {}
}
