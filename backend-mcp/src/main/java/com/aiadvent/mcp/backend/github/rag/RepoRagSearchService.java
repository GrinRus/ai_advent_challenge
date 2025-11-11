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

    RepoRagSearchReranker.PostProcessingResult postProcessingResult;
    RerankStrategy rerankStrategy = resolveRerankStrategy(command.rerankStrategy());
    if (rerankStrategy == RerankStrategy.NONE) {
      postProcessingResult =
          new RepoRagSearchReranker.PostProcessingResult(documents, false, List.of());
    } else {
      int maxContextTokens = resolveMaxContextTokens(command.maxContextTokens());
      RepoRagPostProcessingRequest postProcessingRequest =
          new RepoRagPostProcessingRequest(
              maxContextTokens,
              resolveLocale(command),
              properties.getRerank().getMaxSnippetLines(),
              properties.getPostProcessing().isLlmCompressionEnabled(),
              resolveRerankTopN(command.rerankTopN()));
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
                resolveLocale(command),
                allowEmptyContext));

    if (generationResult.contextMissing() && !allowEmptyContext) {
      throw new IllegalStateException(properties.getGeneration().getEmptyContextMessage());
    }

    List<String> allModules = new ArrayList<>(appliedModules);
    allModules.addAll(generationResult.appliedModules());

    List<SearchMatch> matches = toMatches(postProcessingResult.documents());
    String instructions =
        renderInstructions(
            command,
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

  private String resolveTranslateTo(SearchCommand command) {
    if (StringUtils.hasText(command.translateTo())) {
      return command.translateTo();
    }
    return properties.getQueryTransformers().getDefaultTargetLanguage();
  }

  private boolean resolveAllowEmptyContext(SearchCommand command) {
    if (command.allowEmptyContext() != null) {
      return command.allowEmptyContext();
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
    if (StringUtils.hasText(command.generationLocale())) {
      return command.generationLocale();
    }
    return resolveTranslateTo(command);
  }

  private int resolveRerankTopN(Integer candidate) {
    int defaultTopN = Math.max(1, Math.min(MAX_TOP_K, properties.getRerank().getTopN()));
    if (candidate == null) {
      return defaultTopN;
    }
    return Math.max(1, Math.min(MAX_TOP_K, candidate));
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
      SearchCommand command,
      Query query,
      String augmentedPrompt,
      boolean contextMissing,
      String defaultInstructions) {
    if (!StringUtils.hasText(command.instructionsTemplate())) {
      return defaultInstructions;
    }
    Map<String, String> replacements =
        Map.of(
            "{{rawQuery}}", query.text(),
            "{{repoOwner}}", command.repoOwner(),
            "{{repoName}}", command.repoName(),
            "{{locale}}", resolveLocale(command),
            "{{augmentedPrompt}}", augmentedPrompt,
            "{{contextStatus}}", contextMissing ? "missing" : "ready");
    String instructions = command.instructionsTemplate();
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
}
