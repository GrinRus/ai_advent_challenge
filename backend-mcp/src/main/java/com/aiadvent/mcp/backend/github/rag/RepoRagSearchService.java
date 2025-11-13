package com.aiadvent.mcp.backend.github.rag;

import com.aiadvent.mcp.backend.config.GitHubRagProperties;
import com.aiadvent.mcp.backend.github.rag.persistence.RepoRagNamespaceStateEntity;
import com.aiadvent.mcp.backend.github.rag.postprocessing.RepoRagPostProcessingRequest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionTextParser;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

@Service
public class RepoRagSearchService {

  private final GitHubRagProperties properties;
  private final RepoRagRetrievalPipeline retrievalPipeline;
  private final RepoRagSearchReranker reranker;
  private final RepoRagGenerationService generationService;
  private final RepoRagNamespaceStateService namespaceStateService;
  private final FilterExpressionTextParser filterExpressionParser = new FilterExpressionTextParser();

  public RepoRagSearchService(
      GitHubRagProperties properties,
      RepoRagRetrievalPipeline retrievalPipeline,
      RepoRagSearchReranker reranker,
      RepoRagGenerationService generationService,
      RepoRagNamespaceStateService namespaceStateService) {
    this.properties = Objects.requireNonNull(properties, "properties");
    this.retrievalPipeline = Objects.requireNonNull(retrievalPipeline, "retrievalPipeline");
    this.reranker = Objects.requireNonNull(reranker, "reranker");
    this.generationService = Objects.requireNonNull(generationService, "generationService");
    this.namespaceStateService =
        Objects.requireNonNull(namespaceStateService, "namespaceStateService");
  }

  public SearchResponse search(SearchCommand command) {
    validate(command);
    RagParameterGuard.ResolvedSearchPlan plan =
        Objects.requireNonNull(command.plan(), "plan must not be null");
    RepoRagNamespaceStateEntity state =
        namespaceStateService
            .findByRepoOwnerAndRepoName(command.repoOwner(), command.repoName())
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Namespace repo:%s/%s has not been indexed yet"
                            .formatted(command.repoOwner(), command.repoName())));
    if (!state.isReady()) {
      throw new IllegalStateException(
          "Namespace repo:%s/%s is still indexing"
              .formatted(command.repoOwner(), command.repoName()));
    }
    String namespace = state.getNamespace();
    List<String> serviceWarnings = new ArrayList<>();

    RetrievalAttemptResult primaryAttempt =
        executeRepoAttempt(
            command.rawQuery(),
            plan,
            namespace,
            command.history(),
            command.previousAssistantReply(),
            false);

    RetrievalAttemptResult finalAttempt = primaryAttempt;
    boolean lowThresholdApplied = false;
    boolean overviewSeedApplied = false;
    boolean overviewRetry =
        primaryAttempt.documents().isEmpty()
            && shouldUseOverviewFallback(command.rawQuery(), plan);
    boolean identifierRetry =
        primaryAttempt.documents().isEmpty()
            && !overviewRetry
            && shouldUseIdentifierFallback(command.rawQuery(), plan);
    if (overviewRetry || identifierRetry) {
      lowThresholdApplied = true;
      RagParameterGuard.ResolvedSearchPlan fallbackPlan = createLowThresholdPlan(plan);
      String retryQuery =
          overviewRetry
              ? buildSeededRawQuery(command.rawQuery(), plan.overviewBoostKeywords())
              : command.rawQuery();
      RetrievalAttemptResult fallbackAttempt =
          executeRepoAttempt(
              retryQuery,
              fallbackPlan,
              namespace,
              command.history(),
              command.previousAssistantReply(),
              overviewRetry);
      if (overviewRetry) {
        overviewSeedApplied = true;
        serviceWarnings.add(
            "Обзорный запрос выполнен повторно: понижен minScore и добавлены подсказки README/backlog.");
      } else {
        serviceWarnings.add(
            "Запрос похож на идентификатор: выполнен повторный поиск с пониженным minScore.");
      }
      if (!fallbackAttempt.documents().isEmpty()) {
        finalAttempt = fallbackAttempt;
      }
    }

    RepoRagGenerationService.GenerationResult generationResult =
        generateResult(command, finalAttempt);

    List<String> allModules = new ArrayList<>(finalAttempt.appliedModules());
    if (lowThresholdApplied || finalAttempt.plan().mode() == RagParameterGuard.SearchPlanMode.LOW_THRESHOLD) {
      allModules.add("retrieval.low-threshold");
    }
    if (overviewSeedApplied) {
      allModules.add("retrieval.overview-seed");
    }
    allModules.addAll(generationResult.appliedModules());
    allModules.add("profile:" + plan.profileName());

    List<SearchMatch> matches = toMatches(finalAttempt.documents());
    boolean noResults = matches.isEmpty();
    return new SearchResponse(
        matches,
        finalAttempt.rerankApplied(),
        generationResult.rawAugmentedPrompt(),
        generationResult.summaryAugmentedPrompt(),
        generationResult.contextMissing(),
        noResults,
        generationResult.noResultsReason(),
        allModules,
        serviceWarnings.isEmpty() ? List.of() : List.copyOf(serviceWarnings),
        generationResult.summary(),
        generationResult.rawAnswer());
  }

  public SearchResponse searchGlobal(GlobalSearchCommand command) {
    validateGlobal(command);
    RagParameterGuard.ResolvedSearchPlan plan =
        Objects.requireNonNull(command.plan(), "plan must not be null");
    Query query =
        retrievalPipeline.buildQuery(
            command.rawQuery(),
            command.history(),
            command.previousAssistantReply(),
            properties.getQueryTransformers().getMaxHistoryTokens());

    RepoRagRetrievalPipeline.PipelineInput pipelineInput =
        new RepoRagRetrievalPipeline.PipelineInput(
            query,
            null,
            plan.multiQuery(),
            plan.topK(),
            plan.topKPerQuery(),
            plan.minScore(),
            defaultTranslateTo(),
            properties.getPostProcessing().isLlmCompressionEnabled());

    RepoRagRetrievalPipeline.PipelineResult pipelineResult =
        retrievalPipeline.execute(pipelineInput);
    Query finalQuery = ensureQueryText(pipelineResult.finalQuery(), command.rawQuery());
    List<Document> documents =
        applyLanguageThresholds(pipelineResult.documents(), plan.minScoreByLanguage());

    String locale = defaultLocale();
    RepoRagPostProcessingRequest postProcessingRequest =
        buildPostProcessingRequest(locale, plan, resolveMaxContextTokens(null));
    RepoRagSearchReranker.PostProcessingResult postProcessingResult =
        reranker.process(finalQuery, documents, postProcessingRequest);

    List<String> appliedModules = new ArrayList<>(pipelineResult.appliedModules());
    appliedModules.addAll(postProcessingResult.appliedModules());

    RepoRagGenerationService.GenerationResult generationResult =
        generateResult(
            finalQuery,
            postProcessingResult.documents(),
            safeDisplay(command.displayRepoOwner()),
            safeDisplay(command.displayRepoName()),
            command.responseChannel());

    List<String> allModules = new ArrayList<>(appliedModules);
    allModules.addAll(generationResult.appliedModules());
    allModules.add("profile:" + plan.profileName());

    List<SearchMatch> matches = toMatches(postProcessingResult.documents());
    boolean noResults = matches.isEmpty();
    return new SearchResponse(
        matches,
        postProcessingResult.changed(),
        generationResult.rawAugmentedPrompt(),
        generationResult.summaryAugmentedPrompt(),
        generationResult.contextMissing(),
        noResults,
        generationResult.noResultsReason(),
        allModules,
        List.of(),
        generationResult.summary(),
        generationResult.rawAnswer());
  }

  private RetrievalAttemptResult executeRepoAttempt(
      String rawQuery,
      RagParameterGuard.ResolvedSearchPlan plan,
      String namespace,
      List<RepoRagSearchConversationTurn> history,
      String previousAssistantReply,
      boolean preferOverviewDocs) {
    Query query =
        retrievalPipeline.buildQuery(
            rawQuery,
            history,
            previousAssistantReply,
            properties.getQueryTransformers().getMaxHistoryTokens());

    Filter.Expression expression = buildFilterExpression(namespace);

    RepoRagRetrievalPipeline.PipelineInput pipelineInput =
        new RepoRagRetrievalPipeline.PipelineInput(
            query,
            expression,
            plan.multiQuery(),
            plan.topK(),
            plan.topKPerQuery(),
            plan.minScore(),
            defaultTranslateTo(),
            properties.getPostProcessing().isLlmCompressionEnabled());

    RepoRagRetrievalPipeline.PipelineResult pipelineResult =
        retrievalPipeline.execute(pipelineInput);
    Query finalQuery = ensureQueryText(pipelineResult.finalQuery(), rawQuery);
    List<Document> documents =
        applyLanguageThresholds(pipelineResult.documents(), plan.minScoreByLanguage());
    if (preferOverviewDocs) {
      documents = prioritizeOverviewDocuments(documents);
    }

    String locale = defaultLocale();
    RepoRagPostProcessingRequest postProcessingRequest =
        buildPostProcessingRequest(locale, plan, resolveMaxContextTokens(null));
    RepoRagSearchReranker.PostProcessingResult postProcessingResult =
        reranker.process(finalQuery, documents, postProcessingRequest);

    List<String> appliedModules = new ArrayList<>(pipelineResult.appliedModules());
    appliedModules.addAll(postProcessingResult.appliedModules());
    return new RetrievalAttemptResult(
        finalQuery, postProcessingResult.documents(), appliedModules, postProcessingResult.changed(), plan);
  }

  private RepoRagGenerationService.GenerationResult generateResult(
      SearchCommand command, RetrievalAttemptResult attempt) {
    return generateResult(
        attempt.finalQuery(),
        attempt.documents(),
        command.repoOwner(),
        command.repoName(),
        command.responseChannel());
  }

  private RepoRagGenerationService.GenerationResult generateResult(
      Query finalQuery,
      List<Document> documents,
      String repoOwner,
      String repoName,
      RepoRagResponseChannel responseChannel) {
    boolean allowEmptyContext = properties.getGeneration().isAllowEmptyContext();
    try {
      RepoRagGenerationService.GenerationResult result =
          generationService.generate(
              new RepoRagGenerationService.GenerationCommand(
                  finalQuery,
                  documents,
                  repoOwner,
                  repoName,
                  defaultLocale(),
                  allowEmptyContext,
                  responseChannel));
      if (result.contextMissing() && !allowEmptyContext) {
        throw new IllegalStateException(properties.getGeneration().getEmptyContextMessage());
      }
      return result;
    } catch (IllegalArgumentException ex) {
      throw enrichGenerationException(ex);
    }
  }

  private boolean shouldUseOverviewFallback(
      String rawQuery, RagParameterGuard.ResolvedSearchPlan plan) {
    if (!hasFallback(plan)) {
      return false;
    }
    String classifier = plan.minScoreClassifier();
    if (!StringUtils.hasText(classifier)) {
      return false;
    }
    if ("overview".equals(classifier)) {
      return OverviewQueryClassifier.isOverview(rawQuery, plan.overviewBoostKeywords());
    }
    return false;
  }

  private boolean shouldUseIdentifierFallback(
      String rawQuery, RagParameterGuard.ResolvedSearchPlan plan) {
    return hasFallback(plan) && RepoRagQueryHeuristics.isCodeIdentifier(rawQuery);
  }

  private boolean hasFallback(RagParameterGuard.ResolvedSearchPlan plan) {
    return plan.fallbackMinScore() != null;
  }

  private RagParameterGuard.ResolvedSearchPlan createLowThresholdPlan(
      RagParameterGuard.ResolvedSearchPlan plan) {
    double fallbackMinScore =
        plan.fallbackMinScore() != null
            ? plan.fallbackMinScore()
            : Math.max(0.1d, plan.minScore() * 0.8d);
    RepoRagMultiQueryOptions forcedMultiQuery = forceMultiQueryOptions(plan);
    return plan.withOverrides(
        RagParameterGuard.SearchPlanMode.LOW_THRESHOLD, fallbackMinScore, forcedMultiQuery);
  }

  private RepoRagMultiQueryOptions forceMultiQueryOptions(
      RagParameterGuard.ResolvedSearchPlan plan) {
    RepoRagMultiQueryOptions source = plan.multiQuery();
    int defaultQueries = Math.max(3, properties.getMultiQuery().getDefaultQueries());
    int maxQueries = Math.max(1, properties.getMultiQuery().getMaxQueries());
    int targetQueries =
        source != null && source.queries() != null ? source.queries() : defaultQueries;
    targetQueries = Math.max(targetQueries, defaultQueries);
    targetQueries = Math.min(targetQueries, maxQueries);
    Integer sourceMax = source != null ? source.maxQueries() : null;
    int targetMax = sourceMax != null ? Math.min(sourceMax, maxQueries) : maxQueries;
    return new RepoRagMultiQueryOptions(true, targetQueries, targetMax);
  }

  private String buildSeededRawQuery(String rawQuery, List<String> keywords) {
    StringBuilder builder = new StringBuilder();
    if (StringUtils.hasText(rawQuery)) {
      builder.append(rawQuery.trim());
    }
    builder.append("\n\n");
    builder.append("Нужен обзор проекта: README.md, docs/backlog.md, ключевые особенности.");
    if (!CollectionUtils.isEmpty(keywords)) {
      builder.append(" Ключевые фразы: ").append(String.join(", ", keywords));
    }
    return builder.toString();
  }

  private List<Document> prioritizeOverviewDocuments(List<Document> documents) {
    if (CollectionUtils.isEmpty(documents)) {
      return documents;
    }
    List<Document> preferred = new ArrayList<>();
    List<Document> rest = new ArrayList<>();
    for (Document document : documents) {
      if (isOverviewPath(document)) {
        preferred.add(document);
      } else {
        rest.add(document);
      }
    }
    if (preferred.isEmpty()) {
      return documents;
    }
    preferred.addAll(rest);
    return preferred;
  }

  private boolean isOverviewPath(Document document) {
    Map<String, Object> metadata = document.getMetadata();
    if (metadata == null) {
      return false;
    }
    Object path = metadata.get("file_path");
    if (!(path instanceof String str) || !StringUtils.hasText(str)) {
      return false;
    }
    String normalized = str.toLowerCase(Locale.ROOT);
    return normalized.contains("readme") || normalized.contains("docs/backlog");
  }

  private record RetrievalAttemptResult(
      Query finalQuery,
      List<Document> documents,
      List<String> appliedModules,
      boolean rerankApplied,
      RagParameterGuard.ResolvedSearchPlan plan) {}

  private static final class OverviewQueryClassifier {
    private static final List<String> KEYWORDS =
        List.of(
            "что за проект",
            "что делает проект",
            "описание проекта",
            "project overview",
            "project summary",
            "обзор проекта",
            "что он делает");

    private OverviewQueryClassifier() {}

    static boolean isOverview(String rawQuery, List<String> extraKeywords) {
      if (!StringUtils.hasText(rawQuery)) {
        return false;
      }
      String normalized = rawQuery.toLowerCase(Locale.ROOT);
      for (String keyword : KEYWORDS) {
        if (normalized.contains(keyword)) {
          return true;
        }
      }
      if (extraKeywords != null) {
        for (String keyword : extraKeywords) {
          if (normalized.contains(keyword)) {
            return true;
          }
        }
      }
      return normalized.length() < 72 && Pattern.compile("что|что-то|описание").matcher(normalized).find();
    }
  }

  private List<SearchMatch> toMatches(List<Document> documents) {
    List<SearchMatch> matches = new ArrayList<>();
    for (Document document : documents) {
      Map<String, Object> metadata = document.getMetadata();
      String path = metadata != null ? (String) metadata.getOrDefault("file_path", "") : "";
      String summary = metadata != null ? (String) metadata.getOrDefault("summary", "") : "";
      String snippet = buildSnippet(document.getText());
      double score = document.getScore() != null ? document.getScore() : 0d;
      matches.add(new SearchMatch(path, snippet, summary, score, metadata));
    }
    return matches;
  }

  private Filter.Expression buildFilterExpression(String namespace) {
    String expressionText = "namespace == '%s'".formatted(escapeLiteral(namespace));
    try {
      return filterExpressionParser.parse(expressionText);
    } catch (RuntimeException ex) {
      throw new IllegalArgumentException("Invalid namespace filter", ex);
    }
  }

  private List<Document> applyLanguageThresholds(
      List<Document> documents, Map<String, Double> thresholds) {
    if (CollectionUtils.isEmpty(documents) || thresholds == null || thresholds.isEmpty()) {
      return documents;
    }
    Map<String, Double> normalized = new HashMap<>();
    thresholds.forEach(
        (language, value) -> {
          if (StringUtils.hasText(language) && value != null) {
            normalized.put(language.toLowerCase(Locale.ROOT), clampScore(value));
          }
        });
    if (normalized.isEmpty()) {
      return documents;
    }
    return documents.stream()
        .filter(
            document -> {
              Map<String, Object> metadata = document.getMetadata();
              if (metadata == null) {
                return true;
              }
              Object language = metadata.get("language");
              if (!(language instanceof String lang) || !StringUtils.hasText(lang)) {
                return true;
              }
              Double threshold = normalized.get(lang.toLowerCase(Locale.ROOT));
              if (threshold == null) {
                return true;
              }
              Double score = document.getScore();
              return score == null || score >= threshold;
            })
        .collect(Collectors.toList());
  }

  private String escapeLiteral(String value) {
    if (value == null) {
      return "";
    }
    return value.replace("'", "\\'");
  }

  private String buildSnippet(String text) {
    if (!StringUtils.hasText(text)) {
      return "";
    }
    int maxLines = Math.max(1, properties.getRerank().getMaxSnippetLines());
    return text.lines().limit(maxLines).collect(Collectors.joining("\n"));
  }

  private void validate(SearchCommand command) {
    if (!StringUtils.hasText(command.repoOwner()) || !StringUtils.hasText(command.repoName())) {
      throw new IllegalArgumentException("repoOwner and repoName are required");
    }
    if (!StringUtils.hasText(command.rawQuery())) {
      throw new IllegalArgumentException("rawQuery must not be blank");
    }
  }

  private void validateGlobal(GlobalSearchCommand command) {
    if (!StringUtils.hasText(command.rawQuery())) {
      throw new IllegalArgumentException("rawQuery must not be blank");
    }
  }

  private double clampScore(double value) {
    return Math.max(0.1d, Math.min(0.99d, value));
  }

  private String defaultTranslateTo() {
    return properties.getQueryTransformers().getDefaultTargetLanguage();
  }

  private String defaultLocale() {
    return defaultTranslateTo();
  }

  private Query ensureQueryText(Query candidate, String fallback) {
    String text = candidate != null ? candidate.text() : null;
    if (!StringUtils.hasText(text)) {
      text = fallback;
    }
    if (!StringUtils.hasText(text)) {
      throw new IllegalArgumentException("rawQuery must not be blank");
    }
    List<org.springframework.ai.chat.messages.Message> history =
        candidate != null && candidate.history() != null ? candidate.history() : List.of();
    Map<String, Object> context =
        candidate != null && candidate.context() != null ? candidate.context() : Map.of();
    if (candidate != null && text.equals(candidate.text())) {
      return candidate;
    }
    return Query.builder().text(text).history(history).context(context).build();
  }

  private IllegalArgumentException enrichGenerationException(IllegalArgumentException ex) {
    String message = ex.getMessage();
    if (!StringUtils.hasText(message)) {
      return ex;
    }
    if (message.contains("Missing variable names")) {
      String missing = extractBracketContent(message);
      String detailed =
          "LLM prompt требует заполнить переменные "
              + (StringUtils.hasText(missing) ? missing : "query/context")
              + ". Убедись, что `rawQuery` не пустой и инструкции/шаблоны включают допустимые плейсхолдеры: "
              + "{{rawQuery}}, {{repoOwner}}, {{repoName}}, {{locale}}, {{augmentedPrompt}}, {{contextStatus}}.";
      return new IllegalArgumentException(detailed, ex);
    }
    return ex;
  }

  private String extractBracketContent(String source) {
    int start = source.indexOf('[');
    int end = source.indexOf(']', start + 1);
    if (start >= 0 && end > start) {
      return source.substring(start + 1, end);
    }
    return "";
  }

  private int resolveMaxContextTokens(Integer candidate) {
    int limit = properties.getPostProcessing().getMaxContextTokens();
    if (candidate != null && candidate > 0) {
      int sanitized = Math.max(256, candidate);
      return Math.min(sanitized, limit);
    }
    return limit;
  }

  private RepoRagPostProcessingRequest buildPostProcessingRequest(
      String locale, RagParameterGuard.ResolvedSearchPlan plan, int maxContextTokens) {
    RepoRagPostProcessingRequest.NeighborStrategy neighborStrategy =
        resolveNeighborStrategy(plan.neighbor().strategy());
    int neighborRadius = plan.neighbor().radius();
    int neighborLimit = plan.neighbor().limit();
    boolean neighborEnabled = resolveNeighborEnabled(neighborStrategy);
    return new RepoRagPostProcessingRequest(
        maxContextTokens,
        locale,
        properties.getRerank().getMaxSnippetLines(),
        properties.getPostProcessing().isLlmCompressionEnabled(),
        plan.rerankTopN(),
        plan.codeAwareEnabled(),
        plan.codeAwareHeadMultiplier(),
        null,
        neighborEnabled,
        neighborRadius,
        neighborLimit,
        neighborStrategy);
  }

  private RepoRagPostProcessingRequest.NeighborStrategy resolveNeighborStrategy(String candidate) {
    GitHubRagProperties.Neighbor neighbor = properties.getPostProcessing().getNeighbor();
    String value =
        StringUtils.hasText(candidate) ? candidate : neighbor.getStrategy();
    if (!StringUtils.hasText(value)) {
      return RepoRagPostProcessingRequest.NeighborStrategy.OFF;
    }
    try {
      return RepoRagPostProcessingRequest.NeighborStrategy.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      throw new IllegalArgumentException("neighborStrategy must be one of OFF, LINEAR, PARENT_SYMBOL, CALL_GRAPH");
    }
  }

  private boolean resolveNeighborEnabled(RepoRagPostProcessingRequest.NeighborStrategy strategy) {
    if (!properties.getPostProcessing().getNeighbor().isEnabled()) {
      return false;
    }
    return strategy != RepoRagPostProcessingRequest.NeighborStrategy.OFF;
  }

  private RerankStrategy resolveRerankStrategy(String strategy) {
    if (!StringUtils.hasText(strategy)) {
      return RerankStrategy.AUTO;
    }
    if ("none".equalsIgnoreCase(strategy)) {
      return RerankStrategy.NONE;
    }
    return RerankStrategy.AUTO;
  }

  public record SearchCommand(
      String repoOwner,
      String repoName,
      String rawQuery,
      RagParameterGuard.ResolvedSearchPlan plan,
      List<RepoRagSearchConversationTurn> history,
      String previousAssistantReply,
      RepoRagResponseChannel responseChannel) {}

  public record GlobalSearchCommand(
      String rawQuery,
      RagParameterGuard.ResolvedSearchPlan plan,
      List<RepoRagSearchConversationTurn> history,
      String previousAssistantReply,
      String displayRepoOwner,
      String displayRepoName,
      RepoRagResponseChannel responseChannel) {}

  public record SearchResponse(
      List<SearchMatch> matches,
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

  public record SearchMatch(
      String path, String snippet, String summary, double score, Map<String, Object> metadata) {}

  private enum RerankStrategy {
    AUTO,
    NONE
  }

  private String safeDisplay(String value) {
    if (StringUtils.hasText(value)) {
      return value;
    }
    return "global";
  }
}
