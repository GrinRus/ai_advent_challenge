package com.aiadvent.mcp.backend.github.rag;

import com.aiadvent.mcp.backend.config.GitHubRagProperties;
import com.aiadvent.mcp.backend.github.rag.persistence.RepoRagNamespaceStateEntity;
import java.util.ArrayList;
import java.util.List;
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
  private final RepoRagNamespaceStateService namespaceStateService;
  private final FilterExpressionTextParser filterExpressionParser = new FilterExpressionTextParser();

  public RepoRagSearchService(
      GitHubRagProperties properties,
      RepoRagRetrievalPipeline retrievalPipeline,
      RepoRagSearchReranker reranker,
      RepoRagNamespaceStateService namespaceStateService) {
    this.properties = Objects.requireNonNull(properties, "properties");
    this.retrievalPipeline = Objects.requireNonNull(retrievalPipeline, "retrievalPipeline");
    this.reranker = Objects.requireNonNull(reranker, "reranker");
    this.namespaceStateService =
        Objects.requireNonNull(namespaceStateService, "namespaceStateService");
  }

  public SearchResponse search(SearchCommand command) {
    validate(command);
    int topK = resolveTopK(command.topK());
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
            minScore,
            resolveTranslateTo(command));

    RepoRagRetrievalPipeline.PipelineResult pipelineResult =
        retrievalPipeline.execute(pipelineInput);
    List<Document> documents = applyPathFilters(pipelineResult.documents(), command.filters());

    boolean contextMissing = documents.isEmpty();
    if (contextMissing && Boolean.FALSE.equals(command.allowEmptyContext())) {
      throw new IllegalStateException("Индекс не содержит подходящих документов");
    }
    int maxContextTokens = resolveMaxContextTokens(command.maxContextTokens());
    RepoRagPostProcessingRequest postProcessingRequest =
        new RepoRagPostProcessingRequest(
            maxContextTokens,
            resolveLocale(command),
            properties.getRerank().getMaxSnippetLines(),
            properties.getPostProcessing().isLlmCompressionEnabled());

    RepoRagSearchReranker.PostProcessingResult postProcessingResult =
        reranker.process(pipelineResult.finalQuery(), documents, postProcessingRequest);

    List<String> appliedModules = new ArrayList<>(pipelineResult.appliedModules());
    appliedModules.addAll(postProcessingResult.appliedModules());

    List<SearchMatch> matches = toMatches(postProcessingResult.documents());
    String augmentedPrompt = buildAugmentedPrompt(pipelineResult.finalQuery(), matches);
    String instructions =
        renderInstructions(command, pipelineResult.finalQuery(), augmentedPrompt, contextMissing);

    return new SearchResponse(
        matches,
        postProcessingResult.changed(),
        augmentedPrompt,
        instructions,
        contextMissing,
        appliedModules);
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

  private double resolveMinScore(Double candidate) {
    if (candidate == null) {
      return DEFAULT_MIN_SCORE;
    }
    return Math.max(0.1d, Math.min(0.99d, candidate));
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
  }

  private String resolveTranslateTo(SearchCommand command) {
    if (StringUtils.hasText(command.translateTo())) {
      return command.translateTo();
    }
    return properties.getQueryTransformers().getDefaultTargetLanguage();
  }

  private int resolveMaxContextTokens(Integer candidate) {
    if (candidate != null && candidate > 0) {
      return candidate;
    }
    return properties.getPostProcessing().getMaxContextTokens();
  }

  private String resolveLocale(SearchCommand command) {
    if (StringUtils.hasText(command.generationLocale())) {
      return command.generationLocale();
    }
    return resolveTranslateTo(command);
  }

  private String buildAugmentedPrompt(Query query, List<SearchMatch> matches) {
    StringBuilder builder =
        new StringBuilder("Запрос:\n").append(query.text()).append("\n\nКонтекст:\n");
    for (int i = 0; i < Math.min(5, matches.size()); i++) {
      SearchMatch match = matches.get(i);
      builder
          .append(i + 1)
          .append(". ")
          .append(match.path())
          .append("\n")
          .append(match.snippet())
          .append("\n\n");
    }
    return builder.toString().trim();
  }

  private String renderInstructions(
      SearchCommand command, Query query, String augmentedPrompt, boolean contextMissing) {
    String template =
        StringUtils.hasText(command.instructionsTemplate())
            ? command.instructionsTemplate()
            : """
Запусти рассуждение на языке {{locale}}, используя контекст GitHub.
Если список контекста пуст, сообщи: \"Индекс не содержит подходящих документов\".
Запрос: {{rawQuery}}
Контекст:\n{{augmentedPrompt}}
""";
    Map<String, String> replacements =
        Map.of(
            "{{rawQuery}}", query.text(),
            "{{repoOwner}}", command.repoOwner(),
            "{{repoName}}", command.repoName(),
            "{{locale}}", resolveLocale(command),
            "{{augmentedPrompt}}", augmentedPrompt,
            "{{contextStatus}}", contextMissing ? "missing" : "ready");
    String instructions = template;
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
      Double minScore,
      Integer rerankTopN,
      RepoRagSearchFilters filters,
      String filterExpression,
      List<RepoRagSearchConversationTurn> history,
      String previousAssistantReply,
      Boolean allowEmptyContext,
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
      List<String> appliedModules) {}

  public record SearchMatch(
      String path, String snippet, String summary, double score, Map<String, Object> metadata) {}
}
