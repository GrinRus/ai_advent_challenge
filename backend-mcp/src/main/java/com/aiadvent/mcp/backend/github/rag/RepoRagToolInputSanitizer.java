package com.aiadvent.mcp.backend.github.rag;

import com.aiadvent.mcp.backend.config.GitHubRagProperties;
import com.aiadvent.mcp.backend.github.GitHubRepositoryFetchRegistry;
import com.aiadvent.mcp.backend.github.rag.persistence.RepoRagNamespaceStateEntity;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Sanitizes repo.rag tool inputs to shield backend from invalid agent payloads.
 */
@Component
public class RepoRagToolInputSanitizer {

  private static final Logger log = LoggerFactory.getLogger(RepoRagToolInputSanitizer.class);
  private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{\\{([^}]+)}}");
  private static final Map<String, String> PLACEHOLDER_CANONICAL =
      Map.of(
          "rawquery", "rawQuery",
          "repoowner", "repoOwner",
          "reponame", "repoName",
          "locale", "locale",
          "augmentedprompt", "augmentedPrompt",
          "contextstatus", "contextStatus");
  private static final Map<String, String> PLACEHOLDER_SYNONYMS =
      Map.of(
          "query", "rawQuery",
          "question", "rawQuery");
  private static final Map<String, String> LANGUAGE_SYNONYMS =
      Map.ofEntries(
          Map.entry("js", "javascript"),
          Map.entry("nodejs", "javascript"),
          Map.entry("ts", "typescript"),
          Map.entry("tsx", "typescript"),
          Map.entry("py", "python"),
          Map.entry("python3", "python"),
          Map.entry("kt", "kotlin"),
          Map.entry("kts", "kotlin"),
          Map.entry("golang", "go"),
          Map.entry("c#", "csharp"),
          Map.entry("csharp", "csharp"),
          Map.entry("c++", "cpp"),
          Map.entry("cplusplus", "cpp"));
  private static final Map<String, String> LOCALE_SYNONYMS =
      Map.ofEntries(
          Map.entry("ru", "ru"),
          Map.entry("russian", "ru"),
          Map.entry("русский", "ru"),
          Map.entry("en", "en"),
          Map.entry("english", "en"),
          Map.entry("английский", "en"));
  private static final Map<String, String> NEIGHBOR_STRATEGY_SYNONYMS =
      Map.ofEntries(
          Map.entry("off", "OFF"),
          Map.entry("disabled", "OFF"),
          Map.entry("none", "OFF"),
          Map.entry("linear", "LINEAR"),
          Map.entry("line", "LINEAR"),
          Map.entry("default", "LINEAR"),
          Map.entry("parent", "PARENT_SYMBOL"),
          Map.entry("parent_symbol", "PARENT_SYMBOL"),
          Map.entry("parentsymbol", "PARENT_SYMBOL"),
          Map.entry("call_graph", "CALL_GRAPH"),
          Map.entry("callgraph", "CALL_GRAPH"),
          Map.entry("graph", "CALL_GRAPH"));
  private static final Set<String> SUPPORTED_NEIGHBOR_STRATEGIES =
      Set.of("OFF", "LINEAR", "PARENT_SYMBOL", "CALL_GRAPH");
  private static final Set<String> UNIVERSAL_GLOBS =
      Set.of("*", "**", "**/*", "*/**", "./**/*");
  private static final int MAX_TOP_K = 40; // keep in sync with RepoRagSearchService
  private static final int ABSOLUTE_MAX_NEIGHBOR_LIMIT = 400;
  private static final double MIN_SCORE = 0.1d;
  private static final double MAX_SCORE = 0.99d;

  private final GitHubRagProperties properties;
  private final GitHubRepositoryFetchRegistry fetchRegistry;
  private final RepoRagNamespaceStateService namespaceStateService;
  private final MeterRegistry meterRegistry;
  private final Map<String, Counter> fixCounters = new ConcurrentHashMap<>();

  public RepoRagToolInputSanitizer(
      GitHubRagProperties properties,
      GitHubRepositoryFetchRegistry fetchRegistry,
      RepoRagNamespaceStateService namespaceStateService,
      @Nullable MeterRegistry meterRegistry) {
    this.properties = Objects.requireNonNull(properties, "properties");
    this.fetchRegistry = Objects.requireNonNull(fetchRegistry, "fetchRegistry");
    this.namespaceStateService =
        Objects.requireNonNull(namespaceStateService, "namespaceStateService");
    this.meterRegistry = meterRegistry != null ? meterRegistry : new SimpleMeterRegistry();
  }

  public SanitizationResult<RepoRagTools.RepoRagSearchInput> sanitizeSearch(
      RepoRagTools.RepoRagSearchInput input) {
    Objects.requireNonNull(input, "input");
    List<String> warnings = new ArrayList<>();

    ReadyNamespace readyNamespace = null;
    String repoOwner = normalizeSlug(input.repoOwner());
    if (!StringUtils.hasText(repoOwner)) {
      readyNamespace = resolveReadyNamespace().orElse(null);
      if (readyNamespace == null) {
        throw new IllegalStateException(
            "Не найден READY namespace: выполните github.repository_fetch");
      }
      repoOwner = readyNamespace.repoOwner();
      warnings.add(
          "repoOwner заполнен автоматически значением %s/%s"
              .formatted(repoOwner, readyNamespace.repoName()));
    }

    String repoName = normalizeSlug(input.repoName());
    if (!StringUtils.hasText(repoName)) {
      if (readyNamespace == null) {
        readyNamespace =
            resolveReadyNamespace()
                .orElseThrow(
                    () ->
                        new IllegalStateException(
                            "Не найден READY namespace: выполните github.repository_fetch"));
      }
      repoName = readyNamespace.repoName();
      warnings.add(
          "repoName заполнен автоматически значением %s/%s"
              .formatted(readyNamespace.repoOwner(), repoName));
    }

    String rawQuery = normalizeRequired(input.rawQuery(), "rawQuery");
    RepoRagSearchFilters filters = normalizeFilters(input.filters(), warnings);
    String filterExpression = trimToNull(input.filterExpression());
    Map<String, Double> minScoreByLanguage =
        normalizeMinScoreByLanguage(input.minScoreByLanguage(), warnings);
    String translateTo = normalizeLocale(input.translateTo(), warnings);
    String generationLocale = normalizeGenerationLocale(input.generationLocale(), translateTo);
    RepoRagMultiQueryOptions multiQuery = normalizeMultiQuery(input.multiQuery(), warnings);
    Integer topK = clampTopK(input.topK(), "topK", warnings);
    Integer topKPerQuery = clampTopK(input.topKPerQuery(), "topKPerQuery", warnings);
    Integer neighborRadius = normalizeNeighborRadius(input.neighborRadius(), warnings);
    Integer neighborLimit = normalizeNeighborLimit(input.neighborLimit(), warnings);
    String neighborStrategy = normalizeNeighborStrategy(input.neighborStrategy(), warnings);
    Double minScore = clampScore(input.minScore(), warnings);
    Double codeAwareHeadMultiplier =
        clampPositive(input.codeAwareHeadMultiplier(), 1.0d, "codeAwareHeadMultiplier", warnings);
    String instructionsTemplate = sanitizeInstructionsTemplate(input.instructionsTemplate(), warnings);

    String rerankStrategy = trimToNull(input.rerankStrategy());

    RepoRagTools.RepoRagSearchInput sanitized =
        new RepoRagTools.RepoRagSearchInput(
            repoOwner,
            repoName,
            rawQuery,
            topK,
            topKPerQuery,
            minScore,
            minScoreByLanguage,
            input.rerankTopN(),
            rerankStrategy,
            input.codeAwareEnabled(),
            codeAwareHeadMultiplier,
            neighborRadius,
            neighborLimit,
            neighborStrategy,
            filters,
            filterExpression,
            defaultHistory(input.history()),
            trimToNull(input.previousAssistantReply()),
            input.allowEmptyContext(),
            input.useCompression(),
            translateTo,
            multiQuery,
            input.maxContextTokens(),
            generationLocale,
            instructionsTemplate);

    return finalizeResult("repo.rag_search", sanitized, warnings);
  }

  public SanitizationResult<RepoRagTools.RepoRagGlobalSearchInput> sanitizeGlobal(
      RepoRagTools.RepoRagGlobalSearchInput input) {
    Objects.requireNonNull(input, "input");
    List<String> warnings = new ArrayList<>();

    String rawQuery = normalizeRequired(input.rawQuery(), "rawQuery");
    RepoRagSearchFilters filters = normalizeFilters(input.filters(), warnings);
    String filterExpression = trimToNull(input.filterExpression());
    Map<String, Double> minScoreByLanguage =
        normalizeMinScoreByLanguage(input.minScoreByLanguage(), warnings);
    String translateTo = normalizeLocale(input.translateTo(), warnings);
    String generationLocale = normalizeGenerationLocale(input.generationLocale(), translateTo);
    RepoRagMultiQueryOptions multiQuery = normalizeMultiQuery(input.multiQuery(), warnings);
    Integer topK = clampTopK(input.topK(), "topK", warnings);
    Integer topKPerQuery = clampTopK(input.topKPerQuery(), "topKPerQuery", warnings);
    Integer neighborRadius = normalizeNeighborRadius(input.neighborRadius(), warnings);
    Integer neighborLimit = normalizeNeighborLimit(input.neighborLimit(), warnings);
    String neighborStrategy = normalizeNeighborStrategy(input.neighborStrategy(), warnings);
    Double minScore = clampScore(input.minScore(), warnings);
    Double codeAwareHeadMultiplier =
        clampPositive(input.codeAwareHeadMultiplier(), 1.0d, "codeAwareHeadMultiplier", warnings);
    String instructionsTemplate = sanitizeInstructionsTemplate(input.instructionsTemplate(), warnings);
    String displayOwner = trimToNull(input.displayRepoOwner());
    String displayName = trimToNull(input.displayRepoName());
    if (!StringUtils.hasText(displayOwner) || !StringUtils.hasText(displayName)) {
      Optional<ReadyNamespace> namespace = resolveReadyNamespace();
      if (namespace.isPresent()) {
        ReadyNamespace ready = namespace.get();
        if (!StringUtils.hasText(displayOwner)) {
          displayOwner = ready.repoOwner();
          warnings.add(
              "displayRepoOwner заполнен по умолчанию значением %s".formatted(displayOwner));
        }
        if (!StringUtils.hasText(displayName)) {
          displayName = ready.repoName();
          warnings.add(
              "displayRepoName заполнен по умолчанию значением %s".formatted(displayName));
        }
      }
    }

    RepoRagTools.RepoRagGlobalSearchInput sanitized =
        new RepoRagTools.RepoRagGlobalSearchInput(
            rawQuery,
            topK,
            topKPerQuery,
            minScore,
            minScoreByLanguage,
            input.rerankTopN(),
            input.codeAwareEnabled(),
            codeAwareHeadMultiplier,
            neighborRadius,
            neighborLimit,
            neighborStrategy,
            filters,
            filterExpression,
            defaultHistory(input.history()),
            trimToNull(input.previousAssistantReply()),
            input.allowEmptyContext(),
            input.useCompression(),
            translateTo,
            multiQuery,
            input.maxContextTokens(),
            generationLocale,
            instructionsTemplate,
            displayOwner,
            displayName);

    return finalizeResult("repo.rag_search_global", sanitized, warnings);
  }

  public SanitizationResult<RepoRagTools.RepoRagSimpleSearchInput> sanitizeSimple(
      RepoRagTools.RepoRagSimpleSearchInput input) {
    Objects.requireNonNull(input, "input");
    String rawQuery = normalizeRequired(input.rawQuery(), "rawQuery");
    RepoRagTools.RepoRagSimpleSearchInput sanitized =
        new RepoRagTools.RepoRagSimpleSearchInput(rawQuery);
    return finalizeResult("repo.rag_search_simple", sanitized, List.of());
  }

  private List<RepoRagSearchConversationTurn> defaultHistory(
      List<RepoRagSearchConversationTurn> history) {
    return history == null ? List.of() : history;
  }

  private <T> SanitizationResult<T> finalizeResult(
      String tool, T value, List<String> warnings) {
    List<String> immutable = warnings.isEmpty() ? List.of() : List.copyOf(warnings);
    if (!immutable.isEmpty()) {
      Counter counter =
          fixCounters.computeIfAbsent(
              tool,
              key ->
                  Counter.builder("repo_rag_tool_input_fix_total")
                      .tag("tool", key)
                      .description("Количество автоисправлений входных параметров repo.rag_*")
                      .register(meterRegistry));
      counter.increment(immutable.size());
      log.info("{}: применено {} автокоррекций -> {}", tool, immutable.size(), immutable);
    }
    return new SanitizationResult<>(value, immutable);
  }

  private RepoRagSearchFilters normalizeFilters(
      @Nullable RepoRagSearchFilters filters, List<String> warnings) {
    if (filters == null) {
      return null;
    }
    boolean hadLanguages = filters.languages() != null && !filters.languages().isEmpty();
    boolean hadGlobs = filters.pathGlobs() != null && !filters.pathGlobs().isEmpty();

    List<String> normalizedLanguages = new ArrayList<>();
    if (filters.languages() != null) {
      for (String language : filters.languages()) {
        String canonical = canonicalLanguage(language);
        if (canonical == null) {
          continue;
        }
        if (!equalsIgnoreCase(language, canonical)) {
          warnings.add(
              "Язык фильтра %s заменён на %s".formatted(language == null ? "" : language, canonical));
        }
        normalizedLanguages.add(canonical);
      }
    }

    List<String> normalizedGlobs = new ArrayList<>();
    if (filters.pathGlobs() != null) {
      for (String glob : filters.pathGlobs()) {
        String trimmed = trimToNull(glob);
        if (!StringUtils.hasText(trimmed)) {
          continue;
        }
        if (isUniversalGlob(trimmed)) {
          warnings.add("Глоб %s охватывает все файлы и был удалён".formatted(trimmed));
          continue;
        }
        normalizedGlobs.add(trimmed);
      }
    }

    RepoRagSearchFilters normalized =
        new RepoRagSearchFilters(normalizedLanguages, normalizedGlobs);
    if (!normalized.hasLanguages() && !normalized.hasPathGlobs()) {
      if (hadLanguages || hadGlobs) {
        warnings.add("Фильтры очищены: ограничений не осталось");
      }
      return null;
    }
    return normalized;
  }

  private Map<String, Double> normalizeMinScoreByLanguage(
      @Nullable Map<String, Double> source, List<String> warnings) {
    if (source == null || source.isEmpty()) {
      return null;
    }
    Map<String, Double> normalized = new LinkedHashMap<>();
    source.forEach(
        (language, value) -> {
          String canonical = canonicalLanguage(language);
          if (canonical == null || value == null) {
            return;
          }
          double clamped = clampRange(value, MIN_SCORE, MAX_SCORE);
          if (Double.compare(clamped, value) != 0) {
            warnings.add(
                "minScoreByLanguage[%s] ограничен до %.2f"
                    .formatted(canonical, clamped));
          }
          normalized.put(canonical, clamped);
        });
    if (normalized.isEmpty()) {
      return null;
    }
    return Collections.unmodifiableMap(normalized);
  }

  private Double clampScore(@Nullable Double value, List<String> warnings) {
    if (value == null) {
      return null;
    }
    double clamped = clampRange(value, MIN_SCORE, MAX_SCORE);
    if (Double.compare(clamped, value) != 0) {
      warnings.add("minScore ограничен до %.2f".formatted(clamped));
    }
    return clamped;
  }

  private Double clampPositive(
      @Nullable Double value, double min, String field, List<String> warnings) {
    if (value == null) {
      return null;
    }
    double sanitized = Math.max(min, value);
    if (sanitized != value) {
      warnings.add("%s приведён к %.2f".formatted(field, sanitized));
    }
    return sanitized;
  }

  private Integer clampTopK(@Nullable Integer value, String field, List<String> warnings) {
    if (value == null) {
      return null;
    }
    int sanitized = Math.max(1, Math.min(MAX_TOP_K, value));
    if (sanitized != value) {
      warnings.add("%s ограничен до %d".formatted(field, sanitized));
    }
    return sanitized;
  }

  private Integer normalizeNeighborRadius(@Nullable Integer value, List<String> warnings) {
    GitHubRagProperties.Neighbor neighbor = properties.getPostProcessing().getNeighbor();
    int sanitized =
        value == null ? neighbor.getDefaultRadius() : Math.max(0, value);
    int clamped = Math.min(sanitized, Math.max(0, neighbor.getMaxRadius()));
    if (value != null && clamped != value.intValue()) {
      warnings.add("neighborRadius ограничен до %d".formatted(clamped));
    }
    return clamped;
  }

  private Integer normalizeNeighborLimit(@Nullable Integer value, List<String> warnings) {
    GitHubRagProperties.Neighbor neighbor = properties.getPostProcessing().getNeighbor();
    int sanitized = value == null ? neighbor.getDefaultLimit() : Math.max(0, value);
    int maxAllowed = Math.min(Math.max(0, neighbor.getMaxLimit()), ABSOLUTE_MAX_NEIGHBOR_LIMIT);
    int clamped = Math.min(sanitized, maxAllowed);
    if (value != null && clamped != value.intValue()) {
      warnings.add("neighborLimit ограничен до %d".formatted(clamped));
    }
    return clamped;
  }

  private String normalizeNeighborStrategy(
      @Nullable String strategy, List<String> warnings) {
    String normalized = trimToNull(strategy);
    String defaultStrategy = properties.getPostProcessing().getNeighbor().getStrategy();
    if (!StringUtils.hasText(normalized)) {
      return defaultStrategy;
    }
    String lookup = normalized.trim().toLowerCase(Locale.ROOT).replace('-', '_');
    String canonical = NEIGHBOR_STRATEGY_SYNONYMS.getOrDefault(lookup, normalized.toUpperCase(Locale.ROOT));
    if (!SUPPORTED_NEIGHBOR_STRATEGIES.contains(canonical)) {
      warnings.add(
          "neighborStrategy %s не поддерживается и заменён на %s"
              .formatted(normalized, defaultStrategy));
      return defaultStrategy;
    }
    if (!canonical.equalsIgnoreCase(normalized)) {
      warnings.add("neighborStrategy нормализован до %s".formatted(canonical));
    }
    return canonical;
  }

  private RepoRagMultiQueryOptions normalizeMultiQuery(
      @Nullable RepoRagMultiQueryOptions options, List<String> warnings) {
    GitHubRagProperties.MultiQuery config = properties.getMultiQuery();
    boolean enabled = config.isEnabled();
    Integer queries = config.getDefaultQueries();
    Integer maxQueries = config.getMaxQueries();
    if (options != null) {
      if (options.enabled() != null) {
        enabled = options.enabled();
      }
      if (options.queries() != null) {
        queries = options.queries();
      }
      if (options.maxQueries() != null) {
        maxQueries = options.maxQueries();
      }
    }
    int clampedMax = Math.max(1, maxQueries);
    clampedMax = Math.min(clampedMax, config.getMaxQueries());
    if (clampedMax != maxQueries) {
      warnings.add("multiQuery.maxQueries ограничен до %d".formatted(clampedMax));
    }
    int clampedQueries = Math.max(1, Math.min(queries, clampedMax));
    if (clampedQueries != queries) {
      warnings.add("multiQuery.queries ограничен до %d".formatted(clampedQueries));
    }
    return new RepoRagMultiQueryOptions(enabled, clampedQueries, clampedMax);
  }

  private String sanitizeInstructionsTemplate(
      @Nullable String template, List<String> warnings) {
    String trimmed = trimToNull(template);
    if (!StringUtils.hasText(trimmed)) {
      return null;
    }
    Matcher matcher = PLACEHOLDER_PATTERN.matcher(trimmed);
    StringBuffer buffer = new StringBuffer();
    boolean changed = false;
    while (matcher.find()) {
      String rawToken = matcher.group(1);
      String canonical = canonicalPlaceholder(rawToken);
      if (canonical == null) {
        warnings.add("Удалён неподдерживаемый плейсхолдер %s".formatted(matcher.group()));
        matcher.appendReplacement(buffer, "");
        changed = true;
        continue;
      }
      String replacement = "{{" + canonical + "}}";
      if (!replacement.equals(matcher.group())) {
        warnings.add("Плейсхолдер %s нормализован до %s".formatted(matcher.group(), replacement));
        changed = true;
      }
      matcher.appendReplacement(buffer, Matcher.quoteReplacement(replacement));
    }
    matcher.appendTail(buffer);
    String sanitized = changed ? buffer.toString().trim() : trimmed;
    return sanitized.isEmpty() ? null : sanitized;
  }

  private String canonicalPlaceholder(String token) {
    if (!StringUtils.hasText(token)) {
      return null;
    }
    String normalized = token.trim();
    String lookup = normalized.replace(" ", "").toLowerCase(Locale.ROOT);
    if (PLACEHOLDER_SYNONYMS.containsKey(lookup)) {
      return PLACEHOLDER_SYNONYMS.get(lookup);
    }
    return PLACEHOLDER_CANONICAL.get(lookup);
  }

  private String normalizeLocale(@Nullable String value, List<String> warnings) {
    String trimmed = trimToNull(value);
    if (!StringUtils.hasText(trimmed)) {
      return properties.getQueryTransformers().getDefaultTargetLanguage();
    }
    String lookup = trimmed.trim().toLowerCase(Locale.ROOT);
    String canonical = LOCALE_SYNONYMS.getOrDefault(lookup, lookup);
    if (!canonical.equals(lookup)) {
      warnings.add("translateTo нормализован до %s".formatted(canonical));
    }
    return canonical;
  }

  private String normalizeGenerationLocale(@Nullable String locale, String translateTo) {
    if (StringUtils.hasText(locale)) {
      return locale.trim();
    }
    return translateTo;
  }

  private String normalizeSlug(@Nullable String value) {
    String trimmed = trimToNull(value);
    if (!StringUtils.hasText(trimmed)) {
      return null;
    }
    return trimmed.toLowerCase(Locale.ROOT);
  }

  private String normalizeRequired(@Nullable String value, String field) {
    String normalized = trimToNull(value);
    if (!StringUtils.hasText(normalized)) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return normalized;
  }

  private String trimToNull(@Nullable String value) {
    if (!StringUtils.hasText(value)) {
      return null;
    }
    return value.trim();
  }

  private String canonicalLanguage(@Nullable String value) {
    if (!StringUtils.hasText(value)) {
      return null;
    }
    String trimmed = value.trim();
    String lookup = trimmed.toLowerCase(Locale.ROOT);
    String canonical = LANGUAGE_SYNONYMS.getOrDefault(lookup, lookup);
    return canonical;
  }

  private boolean equalsIgnoreCase(String left, String right) {
    if (left == null) {
      return right == null;
    }
    return left.equalsIgnoreCase(right);
  }

  private Optional<ReadyNamespace> resolveReadyNamespace() {
    return fetchRegistry
        .latest()
        .flatMap(
            context -> {
              String owner = normalizeSlug(context.repoOwner());
              String name = normalizeSlug(context.repoName());
              if (!StringUtils.hasText(owner) || !StringUtils.hasText(name)) {
                return Optional.empty();
              }
              return namespaceStateService
                  .findByRepoOwnerAndRepoName(owner, name)
                  .filter(RepoRagNamespaceStateEntity::isReady);
            })
        .map(entity -> new ReadyNamespace(entity.getRepoOwner(), entity.getRepoName()));
  }

  private boolean isUniversalGlob(String glob) {
    if (!StringUtils.hasText(glob)) {
      return true;
    }
    String normalized = glob.trim();
    return UNIVERSAL_GLOBS.contains(normalized)
        || UNIVERSAL_GLOBS.contains(normalized.toLowerCase(Locale.ROOT));
  }

  private double clampRange(double value, double min, double max) {
    double sanitized = Math.max(min, Math.min(max, value));
    return sanitized;
  }

  private record ReadyNamespace(String repoOwner, String repoName) {}

  public record SanitizationResult<T>(T value, List<String> warnings) {}
}
