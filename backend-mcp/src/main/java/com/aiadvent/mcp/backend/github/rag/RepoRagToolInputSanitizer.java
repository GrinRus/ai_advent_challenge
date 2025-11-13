package com.aiadvent.mcp.backend.github.rag;

import com.aiadvent.mcp.backend.config.GitHubRagProperties;
import com.aiadvent.mcp.backend.github.GitHubRepositoryFetchRegistry;
import com.aiadvent.mcp.backend.github.rag.persistence.RepoRagNamespaceStateEntity;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class RepoRagToolInputSanitizer {

  private static final Logger log = LoggerFactory.getLogger(RepoRagToolInputSanitizer.class);

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
    String profile = normalizeProfile(input.profile(), warnings);
    String responseChannel = normalizeResponseChannel(input.responseChannel(), warnings);
    List<RepoRagSearchConversationTurn> history = defaultHistory(input.history());
    String previousAssistantReply = trimToNull(input.previousAssistantReply());

    RepoRagTools.RepoRagSearchInput sanitized =
        new RepoRagTools.RepoRagSearchInput(
            repoOwner,
            repoName,
            rawQuery,
            profile,
            history,
            previousAssistantReply,
            responseChannel);
    return finalizeResult("repo.rag_search", sanitized, warnings);
  }

  public SanitizationResult<RepoRagTools.RepoRagGlobalSearchInput> sanitizeGlobal(
      RepoRagTools.RepoRagGlobalSearchInput input) {
    Objects.requireNonNull(input, "input");
    List<String> warnings = new ArrayList<>();

    String rawQuery = normalizeRequired(input.rawQuery(), "rawQuery");
    String profile = normalizeProfile(input.profile(), warnings);
    String responseChannel = normalizeResponseChannel(input.responseChannel(), warnings);
    List<RepoRagSearchConversationTurn> history = defaultHistory(input.history());
    String previousAssistantReply = trimToNull(input.previousAssistantReply());
    String displayOwner = trimToNull(input.displayRepoOwner());
    String displayName = trimToNull(input.displayRepoName());

    if (!StringUtils.hasText(displayOwner) || !StringUtils.hasText(displayName)) {
      Optional<ReadyNamespace> ready = resolveReadyNamespace();
      if (ready.isPresent()) {
        ReadyNamespace namespace = ready.get();
        if (!StringUtils.hasText(displayOwner)) {
          displayOwner = namespace.repoOwner();
          warnings.add(
              "displayRepoOwner заполнен по умолчанию значением %s".formatted(displayOwner));
        }
        if (!StringUtils.hasText(displayName)) {
          displayName = namespace.repoName();
          warnings.add(
              "displayRepoName заполнен по умолчанию значением %s".formatted(displayName));
        }
      } else {
        if (!StringUtils.hasText(displayOwner)) {
          displayOwner = "global";
        }
        if (!StringUtils.hasText(displayName)) {
          displayName = "global";
        }
      }
    }

    RepoRagTools.RepoRagGlobalSearchInput sanitized =
        new RepoRagTools.RepoRagGlobalSearchInput(
            rawQuery,
            profile,
            history,
            previousAssistantReply,
            displayOwner,
            displayName,
            responseChannel);
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

  private String normalizeProfile(@Nullable String requested, List<String> warnings) {
    String candidate = trimToNull(requested);
    boolean usedDefault = !StringUtils.hasText(candidate);
    GitHubRagProperties.ResolvedRagParameterProfile resolved = properties.resolveProfile(candidate);
    String canonical = resolved.name();
    if (usedDefault) {
      warnings.add("profile не указан — использован %s".formatted(canonical));
    } else if (!canonical.equals(candidate)) {
      warnings.add("profile нормализован до %s".formatted(canonical));
    }
    return canonical;
  }

  private String normalizeResponseChannel(@Nullable String requested, List<String> warnings) {
    String candidate = trimToNull(requested);
    if (!StringUtils.hasText(candidate)) {
      return "both";
    }
    String normalized = candidate.toLowerCase(Locale.ROOT);
    if (normalized.equals("short")) {
      normalized = "summary";
    } else if (normalized.equals("full")) {
      normalized = "raw";
    }
    if (!List.of("summary", "raw", "both").contains(normalized)) {
      warnings.add("responseChannel сброшен на both: значение '%s' не поддерживается".formatted(candidate));
      return "both";
    }
    return normalized;
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
                      .description("Количество автокоррекций параметров repo.rag_*")
                      .register(meterRegistry));
      counter.increment(immutable.size());
      log.info("{}: применено {} автокоррекций -> {}", tool, immutable.size(), immutable);
    }
    return new SanitizationResult<>(value, immutable);
  }

  private Optional<ReadyNamespace> resolveReadyNamespace() {
    return fetchRegistry
        .latest()
        .flatMap(
            context ->
                namespaceStateService
                    .findByRepoOwnerAndRepoName(
                        normalizeSlug(context.repoOwner()), normalizeSlug(context.repoName()))
                    .filter(RepoRagNamespaceStateEntity::isReady)
                    .map(
                        state ->
                            new ReadyNamespace(state.getRepoOwner(), state.getRepoName())));
  }

  private String normalizeSlug(@Nullable String candidate) {
    if (!StringUtils.hasText(candidate)) {
      return candidate;
    }
    return candidate.trim().toLowerCase(Locale.ROOT);
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
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  public record SanitizationResult<T>(T value, List<String> warnings) {}

  private record ReadyNamespace(String repoOwner, String repoName) {}
}
