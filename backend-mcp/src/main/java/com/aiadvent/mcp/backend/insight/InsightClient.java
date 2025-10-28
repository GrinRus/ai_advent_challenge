package com.aiadvent.mcp.backend.insight;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;

class InsightClient {

  private static final ParameterizedTypeReference<List<InsightSessionSummary>> SESSION_LIST =
      new ParameterizedTypeReference<>() {};

  private final WebClient webClient;

  InsightClient(WebClient insightWebClient) {
    this.webClient = insightWebClient;
  }

  List<InsightSessionSummary> recentSessions(RecentSessionsInput input) {
    return webClient
        .get()
        .uri(
            uriBuilder -> {
              uriBuilder.path("/api/insight/sessions/recent");
              if (!CollectionUtils.isEmpty(input.types())) {
                for (String type : input.types()) {
                  if (StringUtils.hasText(type)) {
                    uriBuilder.queryParam("type", type.trim());
                  }
                }
              }
              if (input.limit() != null) {
                uriBuilder.queryParam("limit", input.limit());
              }
              return uriBuilder.build();
            })
        .accept(MediaType.APPLICATION_JSON)
        .retrieve()
        .bodyToMono(SESSION_LIST)
        .timeout(Duration.ofSeconds(20))
        .onErrorMap(ex -> new InsightClientException("Failed to load recent sessions", ex))
        .blockOptional()
        .orElse(List.of());
  }

  InsightSummaryResponse fetchSummary(FetchSummaryInput input) {
    validateSessionId(input.sessionId());
    String type = normalizeType(input.type());

    return webClient
        .get()
        .uri(
            uriBuilder -> {
              uriBuilder
                  .path("/api/insight/sessions/{sessionId}/summary")
                  .queryParam("type", type);
              if (StringUtils.hasText(input.channel())) {
                uriBuilder.queryParam("channel", input.channel().trim());
              }
              return uriBuilder.build(input.sessionId());
            })
        .accept(MediaType.APPLICATION_JSON)
        .retrieve()
        .bodyToMono(InsightSummaryResponse.class)
        .timeout(Duration.ofSeconds(20))
        .onErrorMap(
            ex ->
                new InsightClientException(
                    "Failed to fetch summary for session " + input.sessionId(), ex))
        .block();
  }

  InsightMemorySearchResponse searchMemory(SearchMemoryInput input) {
    validateSessionId(input.sessionId());
    if (!StringUtils.hasText(input.query())) {
      throw new IllegalArgumentException("query must not be blank");
    }
    String type = normalizeType(input.type());

    return webClient
        .get()
        .uri(
            uriBuilder -> {
              uriBuilder
                  .path("/api/insight/sessions/{sessionId}/memory/search")
                  .queryParam("type", type)
                  .queryParam("q", input.query());
              if (StringUtils.hasText(input.channel())) {
                uriBuilder.queryParam("channel", input.channel().trim());
              }
              if (input.limit() != null) {
                uriBuilder.queryParam("limit", input.limit());
              }
              return uriBuilder.build(input.sessionId());
            })
        .accept(MediaType.APPLICATION_JSON)
        .retrieve()
        .bodyToMono(InsightMemorySearchResponse.class)
        .timeout(Duration.ofSeconds(20))
        .onErrorMap(
            ex ->
                new InsightClientException(
                    "Failed to search memory for session " + input.sessionId(), ex))
        .block();
  }

  InsightMetricsResponse fetchMetrics(FetchMetricsInput input) {
    validateSessionId(input.sessionId());
    String type = normalizeType(input.type());

    return webClient
        .get()
        .uri(
            uriBuilder ->
                uriBuilder
                    .path("/api/insight/sessions/{sessionId}/metrics")
                    .queryParam("type", type)
                    .build(input.sessionId()))
        .accept(MediaType.APPLICATION_JSON)
        .retrieve()
        .bodyToMono(InsightMetricsResponse.class)
        .timeout(Duration.ofSeconds(20))
        .onErrorMap(
            ex ->
                new InsightClientException(
                    "Failed to load metrics for session " + input.sessionId(), ex))
        .block();
  }

  private void validateSessionId(UUID sessionId) {
    if (sessionId == null) {
      throw new IllegalArgumentException("sessionId must be provided");
    }
  }

  private String normalizeType(String value) {
    if (!StringUtils.hasText(value)) {
      throw new IllegalArgumentException("type must be provided");
    }
    return value.trim().toUpperCase();
  }

  record RecentSessionsInput(List<String> types, Integer limit) {}

  record FetchSummaryInput(UUID sessionId, String type, String channel) {}

  record SearchMemoryInput(
      UUID sessionId, String type, String query, String channel, Integer limit) {}

  record FetchMetricsInput(UUID sessionId, String type) {}

  record InsightSessionSummary(
      String type,
      UUID sessionId,
      String title,
      Instant createdAt,
      Instant lastActivityAt,
      String status,
      UUID flowDefinitionId,
      Integer flowDefinitionVersion,
      UUID chatSessionId,
      Long messageCount,
      Long stepCount,
      String summaryPreview) {}

  record InsightSummaryResponse(String type, UUID sessionId, List<Entry> entries) {
    record Entry(
        String channel,
        Long sourceStart,
        Long sourceEnd,
        String summary,
        Long tokenCount,
        String language,
        JsonNode metadata,
        Instant createdAt) {}
  }

  record InsightMemorySearchResponse(
      String type, UUID sessionId, String query, List<MemoryMatch> matches) {
    record MemoryMatch(
        String resourceType,
        String channel,
        String content,
        Instant createdAt,
        Integer sequenceNumber,
        Long version,
        String stepId,
        Integer stepAttempt,
        Long sourceStart,
        Long sourceEnd,
        UUID messageId,
        JsonNode payload) {}
  }

  record InsightMetricsResponse(
      String type,
      UUID sessionId,
      String status,
      Instant startedAt,
      Instant completedAt,
      Instant lastUpdatedAt,
      int stepsCompleted,
      int stepsFailed,
      int retriesScheduled,
      double totalCostUsd,
      long promptTokens,
      long completionTokens) {}
}
