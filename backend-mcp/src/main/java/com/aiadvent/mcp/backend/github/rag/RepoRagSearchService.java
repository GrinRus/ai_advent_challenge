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

  private static final int DEFAULT_TOP_K = 8;
  private static final int MAX_TOP_K = 40;
  private static final double DEFAULT_MIN_SCORE = 0.55d;
  private static final int ABSOLUTE_MAX_NEIGHBOR_LIMIT = 400;

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
    int topK = resolveTopK(command.topK());
    int topKPerQuery = resolveTopKPerQuery(command.topKPerQuery(), topK);
    double minScore = resolveMinScore(command.minScore());
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
          "Namespace repo:%s/%s is still indexing".formatted(command.repoOwner(), command.repoName()));
    }
    String namespace = state.getNamespace();

    Filter.Expression expression = buildFilterExpression(namespace, command);

    Query query =
        retrievalPipeline.buildQuery(
            command.rawQuery(),
            command.history(),
            command.previousAssistantReply(),
            properties.getQueryTransformers().getMaxHistoryTokens());

    RepoRagRetrievalPipeline.PipelineInput pipelineInput =
        new RepoRagRetrievalPipeline.PipelineInput(
            query,
            expression,
            command.multiQuery(),
            topK,
            topKPerQuery,
            minScore,
            resolveTranslateTo(command),
            command.useCompression());

    RepoRagRetrievalPipeline.PipelineResult pipelineResult =
        retrievalPipeline.execute(pipelineInput);
    List<Document> documents =
        applyPathFilters(
            applyLanguageThresholds(
                pipelineResult.documents(), command.minScoreByLanguage()), command.filters());

    String locale = resolveLocale(command);
    RepoRagSearchReranker.PostProcessingResult postProcessingResult;
    RerankStrategy rerankStrategy = resolveRerankStrategy(command.rerankStrategy());
    if (rerankStrategy == RerankStrategy.NONE) {
      postProcessingResult =
          new RepoRagSearchReranker.PostProcessingResult(documents, false, List.of());
    } else {
      int maxContextTokens = resolveMaxContextTokens(command.maxContextTokens());
      RepoRagPostProcessingRequest postProcessingRequest =
          buildPostProcessingRequest(
              locale,
              command.filters(),
              command.rerankTopN(),
              command.codeAwareEnabled(),
              command.codeAwareHeadMultiplier(),
              command.neighborRadius(),
              command.neighborLimit(),
              command.neighborStrategy(),
              maxContextTokens);
      postProcessingResult =
          reranker.process(pipelineResult.finalQuery(), documents, postProcessingRequest);
    }

    List<String> appliedModules = new ArrayList<>(pipelineResult.appliedModules());
    appliedModules.addAll(postProcessingResult.appliedModules());

    boolean allowEmptyContext = resolveAllowEmptyContext(command);
    RepoRagGenerationService.GenerationResult generationResult =
        generationService.generate(
            new RepoRagGenerationService.GenerationCommand(
                pipelineResult.finalQuery(),
                postProcessingResult.documents(),
                command.repoOwner(),
                command.repoName(),
                locale,
                allowEmptyContext));

    if (generationResult.contextMissing() && !allowEmptyContext) {
      throw new IllegalStateException(properties.getGeneration().getEmptyContextMessage());
    }

    List<String> allModules = new ArrayList<>(appliedModules);
    allModules.addAll(generationResult.appliedModules());

    List<SearchMatch> matches = toMatches(postProcessingResult.documents());
    String instructions =
        renderInstructions(
            command.repoOwner(),
            command.repoName(),
            command.instructionsTemplate(),
            locale,
            pipelineResult.finalQuery(),
            generationResult.augmentedPrompt(),
            generationResult.contextMissing(),
            generationResult.instructions());

    boolean noResults = matches.isEmpty();
    return new SearchResponse(
        matches,
        postProcessingResult.changed(),
        generationResult.augmentedPrompt(),
        instructions,
        generationResult.contextMissing(),
        noResults,
        generationResult.noResultsReason(),
        allModules);
  }

  public SearchResponse searchGlobal(GlobalSearchCommand command) {
    validateGlobal(command);
    int topK = resolveTopK(command.topK());
    int topKPerQuery = resolveTopKPerQuery(command.topKPerQuery(), topK);
    double minScore = resolveMinScore(command.minScore());

    Query query =
        retrievalPipeline.buildQuery(
            command.rawQuery(),
            command.history(),
            command.previousAssistantReply(),
            properties.getQueryTransformers().getMaxHistoryTokens());

    Filter.Expression expression = buildGlobalFilterExpression(command);
    RepoRagRetrievalPipeline.PipelineInput pipelineInput =
        new RepoRagRetrievalPipeline.PipelineInput(
            query,
            expression,
            command.multiQuery(),
            topK,
            topKPerQuery,
            minScore,
            resolveTranslateTo(command.translateTo()),
            command.useCompression());

    RepoRagRetrievalPipeline.PipelineResult pipelineResult =
        retrievalPipeline.execute(pipelineInput);
    List<Document> documents =
        applyPathFilters(
            applyLanguageThresholds(
                pipelineResult.documents(), command.minScoreByLanguage()), command.filters());

    String locale = resolveLocale(command.generationLocale(), command.translateTo());
    int maxContextTokens = resolveMaxContextTokens(command.maxContextTokens());
    RepoRagPostProcessingRequest postProcessingRequest =
        buildPostProcessingRequest(
            locale,
            command.filters(),
            command.rerankTopN(),
            command.codeAwareEnabled(),
            command.codeAwareHeadMultiplier(),
            command.neighborRadius(),
            command.neighborLimit(),
            command.neighborStrategy(),
            maxContextTokens);
    RepoRagSearchReranker.PostProcessingResult postProcessingResult =
        reranker.process(pipelineResult.finalQuery(), documents, postProcessingRequest);

    List<String> appliedModules = new ArrayList<>(pipelineResult.appliedModules());
    appliedModules.addAll(postProcessingResult.appliedModules());

    boolean allowEmptyContext = resolveAllowEmptyContext(command.allowEmptyContext());
    RepoRagGenerationService.GenerationResult generationResult =
        generationService.generate(
            new RepoRagGenerationService.GenerationCommand(
                pipelineResult.finalQuery(),
                postProcessingResult.documents(),
                safeDisplay(command.displayRepoOwner()),
                safeDisplay(command.displayRepoName()),
                locale,
                allowEmptyContext));

    if (generationResult.contextMissing() && !allowEmptyContext) {
      throw new IllegalStateException(properties.getGeneration().getEmptyContextMessage());
    }

    List<String> allModules = new ArrayList<>(appliedModules);
    allModules.addAll(generationResult.appliedModules());

    List<SearchMatch> matches = toMatches(postProcessingResult.documents());
    String instructions =
        renderInstructions(
            safeDisplay(command.displayRepoOwner()),
            safeDisplay(command.displayRepoName()),
            command.instructionsTemplate(),
            locale,
            pipelineResult.finalQuery(),
            generationResult.augmentedPrompt(),
            generationResult.contextMissing(),
            generationResult.instructions());

    boolean noResults = matches.isEmpty();
    return new SearchResponse(
        matches,
        postProcessingResult.changed(),
        generationResult.augmentedPrompt(),
        instructions,
        generationResult.contextMissing(),
        noResults,
        generationResult.noResultsReason(),
        allModules);
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

  private Filter.Expression buildFilterExpression(String namespace, SearchCommand command) {
    List<String> clauses = new ArrayList<>();
    clauses.add("namespace == '%s'".formatted(escapeLiteral(namespace)));

    RepoRagSearchFilters filters = command.filters();
    if (filters != null && filters.hasLanguages()) {
      String languageClause =
          filters.languages().stream()
              .map(this::escapeLiteral)
              .map(value -> "'" + value + "'")
              .collect(Collectors.joining(", "));
      clauses.add("language IN [" + languageClause + "]");
    }

    if (StringUtils.hasText(command.filterExpression())) {
      clauses.add("(" + command.filterExpression().trim() + ")");
    }

    String expressionText = String.join(" AND ", clauses);
    try {
      return filterExpressionParser.parse(expressionText);
    } catch (RuntimeException ex) {
      throw new IllegalArgumentException(
          "Invalid filterExpression: " + ex.getMessage(), ex);
    }
  }

  private Filter.Expression buildGlobalFilterExpression(GlobalSearchCommand command) {
    List<String> clauses = new ArrayList<>();

    RepoRagSearchFilters filters = command.filters();
    if (filters != null && filters.hasLanguages()) {
      String languageClause =
          filters.languages().stream()
              .map(this::escapeLiteral)
              .map(value -> "'" + value + "'")
              .collect(Collectors.joining(", "));
      clauses.add("language IN [" + languageClause + "]");
    }

    if (StringUtils.hasText(command.filterExpression())) {
      clauses.add("(" + command.filterExpression().trim() + ")");
    }

    if (clauses.isEmpty()) {
      return null;
    }
    String expressionText = String.join(" AND ", clauses);
    try {
      return filterExpressionParser.parse(expressionText);
    } catch (RuntimeException ex) {
      throw new IllegalArgumentException("Invalid filterExpression: " + ex.getMessage(), ex);
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

  private List<Document> applyPathFilters(
      List<Document> documents, RepoRagSearchFilters filters) {
    if (CollectionUtils.isEmpty(documents) || filters == null || !filters.hasPathGlobs()) {
      return documents;
    }
    List<Pattern> patterns =
        filters.pathGlobs().stream().map(this::compileGlobPattern).toList();
    if (patterns.isEmpty()) {
      return documents;
    }
    return documents.stream()
        .filter(document -> {
          String path = extractPath(document.getMetadata());
          return matchesAny(patterns, path);
        })
        .toList();
  }

  private Pattern compileGlobPattern(String glob) {
    if (!StringUtils.hasText(glob)) {
      return Pattern.compile(".*");
    }
    String normalized = glob.replace('\\', '/');
    StringBuilder regex = new StringBuilder("^");
    for (int i = 0; i < normalized.length(); i++) {
      char ch = normalized.charAt(i);
      switch (ch) {
        case '*':
          regex.append(".*");
          break;
        case '?':
          regex.append('.');
          break;
        case '.':
        case '+':
        case '(':
        case ')':
        case '{':
        case '}':
        case '[':
        case ']':
        case '^':
        case '$':
        case '|':
        case '\\':
          regex.append('\\').append(ch);
          break;
        default:
          regex.append(ch);
      }
    }
    regex.append('$');
    return Pattern.compile(regex.toString());
  }

  private boolean matchesAny(List<Pattern> patterns, String path) {
    if (!StringUtils.hasText(path)) {
      return false;
    }
    String normalized = path.replace('\\', '/');
    for (Pattern pattern : patterns) {
      if (pattern.matcher(normalized).matches()) {
        return true;
      }
    }
    return false;
  }

  private String extractPath(Map<String, Object> metadata) {
    if (metadata == null) {
      return "";
    }
    Object value = metadata.get("file_path");
    return value instanceof String string ? string : "";
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

  private int resolveTopK(Integer candidate) {
    if (candidate == null) {
      return DEFAULT_TOP_K;
    }
    return Math.max(1, Math.min(MAX_TOP_K, candidate));
  }

  private int resolveTopKPerQuery(Integer candidate, int fallback) {
    if (candidate == null) {
      return fallback;
    }
    return Math.max(1, Math.min(MAX_TOP_K, candidate));
  }

  private double resolveMinScore(Double candidate) {
    if (candidate == null) {
      return DEFAULT_MIN_SCORE;
    }
    return clampScore(candidate);
  }

  private double clampScore(double value) {
    return Math.max(0.1d, Math.min(0.99d, value));
  }

  private void validate(SearchCommand command) {
    if (!StringUtils.hasText(command.repoOwner()) || !StringUtils.hasText(command.repoName())) {
      throw new IllegalArgumentException("repoOwner and repoName are required");
    }
    if (!StringUtils.hasText(command.rawQuery())) {
      throw new IllegalArgumentException("rawQuery must not be blank");
    }
    if (command.topK() != null && command.topK() > MAX_TOP_K) {
      throw new IllegalArgumentException("topK must be <= " + MAX_TOP_K);
    }
    if (command.topKPerQuery() != null && command.topKPerQuery() > MAX_TOP_K) {
      throw new IllegalArgumentException("topKPerQuery must be <= " + MAX_TOP_K);
    }
    if (command.rerankTopN() != null) {
      if (command.rerankTopN() < 1) {
        throw new IllegalArgumentException("rerankTopN must be >= 1");
      }
      if (command.rerankTopN() > MAX_TOP_K) {
        throw new IllegalArgumentException("rerankTopN must be <= " + MAX_TOP_K);
      }
    }
    if (command.codeAwareHeadMultiplier() != null) {
      double multiplier = command.codeAwareHeadMultiplier();
      if (multiplier < 1.0d) {
        throw new IllegalArgumentException("codeAwareHeadMultiplier must be >= 1.0");
      }
      double maxMultiplier = properties.getRerank().getCodeAware().getMaxHeadMultiplier();
      if (multiplier > maxMultiplier) {
        throw new IllegalArgumentException(
            "codeAwareHeadMultiplier must be <= " + maxMultiplier);
      }
    }
    if (command.minScoreByLanguage() != null) {
      command.minScoreByLanguage().forEach(
          (language, threshold) -> {
            if (threshold != null && (threshold < 0.1d || threshold > 0.99d)) {
              throw new IllegalArgumentException("minScoreByLanguage values must be between 0.1 and 0.99");
            }
          });
    }
    if (StringUtils.hasText(command.rerankStrategy())) {
      resolveRerankStrategy(command.rerankStrategy());
    }
    if (StringUtils.hasText(command.neighborStrategy())) {
      resolveNeighborStrategy(command.neighborStrategy());
    }
    GitHubRagProperties.Neighbor neighbor = properties.getPostProcessing().getNeighbor();
    if (command.neighborRadius() != null) {
      int radius = command.neighborRadius();
      if (radius < 0) {
        throw new IllegalArgumentException("neighborRadius must be >= 0");
      }
      if (radius > neighbor.getMaxRadius()) {
        throw new IllegalArgumentException("neighborRadius must be <= " + neighbor.getMaxRadius());
      }
    }
    if (command.neighborLimit() != null) {
      int limit = command.neighborLimit();
      if (limit < 0) {
        throw new IllegalArgumentException("neighborLimit must be >= 0");
      }
      int effectiveMax = Math.min(neighbor.getMaxLimit(), ABSOLUTE_MAX_NEIGHBOR_LIMIT);
      if (limit > effectiveMax) {
        throw new IllegalArgumentException("neighborLimit must be <= " + effectiveMax);
      }
    }
    if (command.maxContextTokens() != null
        && command.maxContextTokens() > properties.getPostProcessing().getMaxContextTokens()) {
      throw new IllegalArgumentException(
          "maxContextTokens must be <= " + properties.getPostProcessing().getMaxContextTokens());
    }
    if (command.multiQuery() != null) {
      int limit = properties.getMultiQuery().getMaxQueries();
      RepoRagMultiQueryOptions options = command.multiQuery();
      if (options.queries() != null && options.queries() > limit) {
        throw new IllegalArgumentException("multiQuery.queries must be <= " + limit);
      }
      if (options.maxQueries() != null && options.maxQueries() > limit) {
        throw new IllegalArgumentException("multiQuery.maxQueries must be <= " + limit);
      }
    }
  }

  private void validateGlobal(GlobalSearchCommand command) {
    if (!StringUtils.hasText(command.rawQuery())) {
      throw new IllegalArgumentException("rawQuery must not be blank");
    }
    if (command.topK() != null && command.topK() > MAX_TOP_K) {
      throw new IllegalArgumentException("topK must be <= " + MAX_TOP_K);
    }
    if (command.topKPerQuery() != null && command.topKPerQuery() > MAX_TOP_K) {
      throw new IllegalArgumentException("topKPerQuery must be <= " + MAX_TOP_K);
    }
    if (command.rerankTopN() != null) {
      if (command.rerankTopN() < 1 || command.rerankTopN() > MAX_TOP_K) {
        throw new IllegalArgumentException("rerankTopN must be between 1 and " + MAX_TOP_K);
      }
    }
    if (command.codeAwareHeadMultiplier() != null) {
      double multiplier = command.codeAwareHeadMultiplier();
      if (multiplier < 1.0d) {
        throw new IllegalArgumentException("codeAwareHeadMultiplier must be >= 1.0");
      }
      double maxMultiplier = properties.getRerank().getCodeAware().getMaxHeadMultiplier();
      if (multiplier > maxMultiplier) {
        throw new IllegalArgumentException(
            "codeAwareHeadMultiplier must be <= " + maxMultiplier);
      }
    }
    if (command.minScoreByLanguage() != null) {
      command.minScoreByLanguage().forEach(
          (language, threshold) -> {
            if (threshold != null && (threshold < 0.1d || threshold > 0.99d)) {
              throw new IllegalArgumentException("minScoreByLanguage values must be between 0.1 and 0.99");
            }
          });
    }
    if (StringUtils.hasText(command.filterExpression())) {
      filterExpressionParser.parse(command.filterExpression());
    }
    if (StringUtils.hasText(command.neighborStrategy())) {
      resolveNeighborStrategy(command.neighborStrategy());
    }
    GitHubRagProperties.Neighbor neighbor = properties.getPostProcessing().getNeighbor();
    if (command.neighborRadius() != null) {
      int radius = command.neighborRadius();
      if (radius < 0) {
        throw new IllegalArgumentException("neighborRadius must be >= 0");
      }
      if (radius > neighbor.getMaxRadius()) {
        throw new IllegalArgumentException("neighborRadius must be <= " + neighbor.getMaxRadius());
      }
    }
    if (command.neighborLimit() != null) {
      int limit = command.neighborLimit();
      if (limit < 0) {
        throw new IllegalArgumentException("neighborLimit must be >= 0");
      }
      int effectiveMax = Math.min(neighbor.getMaxLimit(), ABSOLUTE_MAX_NEIGHBOR_LIMIT);
      if (limit > effectiveMax) {
        throw new IllegalArgumentException("neighborLimit must be <= " + effectiveMax);
      }
    }
  }

  private String resolveTranslateTo(SearchCommand command) {
    return resolveTranslateTo(command.translateTo());
  }

  private String resolveTranslateTo(String candidate) {
    if (StringUtils.hasText(candidate)) {
      return candidate;
    }
    return properties.getQueryTransformers().getDefaultTargetLanguage();
  }

  private boolean resolveAllowEmptyContext(SearchCommand command) {
    return resolveAllowEmptyContext(command.allowEmptyContext());
  }

  private boolean resolveAllowEmptyContext(Boolean candidate) {
    if (candidate != null) {
      return candidate;
    }
    return properties.getGeneration().isAllowEmptyContext();
  }

  private int resolveMaxContextTokens(Integer candidate) {
    int limit = properties.getPostProcessing().getMaxContextTokens();
    if (candidate != null && candidate > 0) {
      int sanitized = Math.max(256, candidate);
      return Math.min(sanitized, limit);
    }
    return limit;
  }

  private String resolveLocale(SearchCommand command) {
    return resolveLocale(command.generationLocale(), command.translateTo());
  }

  private String resolveLocale(String generationLocale, String translateTo) {
    if (StringUtils.hasText(generationLocale)) {
      return generationLocale;
    }
    return resolveTranslateTo(translateTo);
  }

  private int resolveRerankTopN(Integer candidate) {
    int defaultTopN = Math.max(1, Math.min(MAX_TOP_K, properties.getRerank().getTopN()));
    if (candidate == null) {
      return defaultTopN;
    }
    return Math.max(1, Math.min(MAX_TOP_K, candidate));
  }

  private RepoRagPostProcessingRequest buildPostProcessingRequest(
      String locale,
      RepoRagSearchFilters filters,
      Integer rerankTopNOverride,
      Boolean codeAwareEnabledOverride,
      Double codeAwareHeadMultiplierOverride,
      Integer neighborRadiusOverride,
      Integer neighborLimitOverride,
      String neighborStrategyOverride,
      int maxContextTokens) {
    int rerankTopN = resolveRerankTopN(rerankTopNOverride);
    boolean codeAwareEnabled = resolveCodeAwareEnabled(codeAwareEnabledOverride);
    double headMultiplier = resolveCodeAwareHeadMultiplier(codeAwareHeadMultiplierOverride);
    String requestedLanguage = resolveRequestedLanguage(filters);
    RepoRagPostProcessingRequest.NeighborStrategy neighborStrategy =
        resolveNeighborStrategy(neighborStrategyOverride);
    int neighborRadius = resolveNeighborRadius(neighborRadiusOverride);
    int neighborLimit = resolveNeighborLimit(neighborLimitOverride);
    boolean neighborEnabled = resolveNeighborEnabled(neighborStrategy);
    return new RepoRagPostProcessingRequest(
        maxContextTokens,
        locale,
        properties.getRerank().getMaxSnippetLines(),
        properties.getPostProcessing().isLlmCompressionEnabled(),
        rerankTopN,
        codeAwareEnabled,
        headMultiplier,
        requestedLanguage,
        neighborEnabled,
        neighborRadius,
        neighborLimit,
        neighborStrategy);
  }

  private boolean resolveCodeAwareEnabled(Boolean candidate) {
    if (candidate != null) {
      return candidate;
    }
    return properties.getRerank().getCodeAware().isEnabled();
  }

  private double resolveCodeAwareHeadMultiplier(Double candidate) {
    GitHubRagProperties.CodeAware codeAware = properties.getRerank().getCodeAware();
    double defaultValue = Math.max(1.0d, codeAware.getDefaultHeadMultiplier());
    double value = candidate != null ? candidate : defaultValue;
    double sanitized = Math.max(1.0d, value);
    double maxAllowed = Math.max(1.0d, codeAware.getMaxHeadMultiplier());
    return Math.min(sanitized, maxAllowed);
  }

  private String resolveRequestedLanguage(RepoRagSearchFilters filters) {
    if (filters == null || !filters.hasLanguages()) {
      return null;
    }
    List<String> languages = filters.languages();
    if (languages.size() != 1) {
      return null;
    }
    String language = languages.get(0);
    if (!StringUtils.hasText(language)) {
      return null;
    }
    return language.trim().toLowerCase(Locale.ROOT);
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

  private int resolveNeighborRadius(Integer candidate) {
    GitHubRagProperties.Neighbor neighbor = properties.getPostProcessing().getNeighbor();
    int maxRadius = Math.max(0, neighbor.getMaxRadius());
    int defaultRadius = Math.max(0, neighbor.getDefaultRadius());
    if (candidate == null) {
      return Math.min(defaultRadius, maxRadius);
    }
    int sanitized = Math.max(0, candidate);
    return Math.min(sanitized, maxRadius);
  }

  private int resolveNeighborLimit(Integer candidate) {
    GitHubRagProperties.Neighbor neighbor = properties.getPostProcessing().getNeighbor();
    int maxLimit = Math.max(0, neighbor.getMaxLimit());
    maxLimit = Math.min(maxLimit, ABSOLUTE_MAX_NEIGHBOR_LIMIT);
    int defaultLimit = Math.max(0, neighbor.getDefaultLimit());
    defaultLimit = Math.min(defaultLimit, maxLimit);
    if (candidate == null) {
      return defaultLimit;
    }
    int sanitized = Math.max(0, candidate);
    return Math.min(sanitized, maxLimit);
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

  private String renderInstructions(
      String repoOwner,
      String repoName,
      String instructionsTemplate,
      String locale,
      Query query,
      String augmentedPrompt,
      boolean contextMissing,
      String defaultInstructions) {
    if (!StringUtils.hasText(instructionsTemplate)) {
      return defaultInstructions;
    }
    Map<String, String> replacements =
        Map.of(
            "{{query}}", query.text(),
            "{{rawQuery}}", query.text(),
            "{{repoOwner}}", repoOwner,
            "{{repoName}}", repoName,
            "{{locale}}", locale,
            "{{augmentedPrompt}}", augmentedPrompt,
            "{{contextStatus}}", contextMissing ? "missing" : "ready");
    String instructions = instructionsTemplate;
    for (Map.Entry<String, String> entry : replacements.entrySet()) {
      instructions = instructions.replace(entry.getKey(), entry.getValue());
    }
    return instructions;
  }

  public record SearchCommand(
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

  public record GlobalSearchCommand(
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

  public record SearchResponse(
      List<SearchMatch> matches,
      boolean rerankApplied,
      String augmentedPrompt,
      String instructions,
      boolean contextMissing,
      boolean noResults,
      String noResultsReason,
      List<String> appliedModules) {}

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
