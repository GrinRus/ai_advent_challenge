package com.aiadvent.mcp.backend.insight;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class InsightTools {

  private final InsightClient client;

  public InsightTools(InsightClient client) {
    this.client = client;
  }

  @Tool(
      name = "insight.recent_sessions",
      description =
          "Возвращает список последних сессий (flow/chat) с ключевыми атрибутами и превью summary.")
  public RecentSessionsResult recentSessions(RecentSessionsInput input) {
    InsightClient.RecentSessionsInput request =
        new InsightClient.RecentSessionsInput(normalizeTypes(input.types()), input.limit());
    List<InsightClient.InsightSessionSummary> sessions = client.recentSessions(request);
    return new RecentSessionsResult(sessions);
  }

  @Tool(
      name = "insight.fetch_summary",
      description =
          "Возвращает список сохранённых summary для указанной сессии (chat или flow).")
  public InsightClient.InsightSummaryResponse fetchSummary(FetchSummaryInput input) {
    if (input == null) {
      throw new IllegalArgumentException("Input must not be null");
    }
    InsightClient.FetchSummaryInput request =
        new InsightClient.FetchSummaryInput(input.sessionId(), input.type(), input.channel());
    return client.fetchSummary(request);
  }

  @Tool(
      name = "insight.search_memory",
      description =
          "Поиск по истории памяти/сообщений для chat или flow. Поддерживает фильтр по каналу.")
  public InsightClient.InsightMemorySearchResponse searchMemory(SearchMemoryInput input) {
    if (input == null) {
      throw new IllegalArgumentException("Input must not be null");
    }
    InsightClient.SearchMemoryInput request =
        new InsightClient.SearchMemoryInput(
            input.sessionId(), input.type(), input.query(), input.channel(), input.limit());
    return client.searchMemory(request);
  }

  @Tool(
      name = "insight.fetch_metrics",
      description =
          "Возвращает агрегированные метрики telemetry для flow-сессии (статус, токены, стоимость).")
  public InsightClient.InsightMetricsResponse fetchMetrics(FetchMetricsInput input) {
    if (input == null) {
      throw new IllegalArgumentException("Input must not be null");
    }
    InsightClient.FetchMetricsInput request =
        new InsightClient.FetchMetricsInput(input.sessionId(), input.type());
    return client.fetchMetrics(request);
  }

  private List<String> normalizeTypes(List<String> values) {
    if (values == null || values.isEmpty()) {
      return List.of();
    }
    List<String> result = new ArrayList<>(values.size());
    for (String value : values) {
      if (StringUtils.hasText(value)) {
        result.add(value.trim().toUpperCase());
      }
    }
    return result;
  }

  public record RecentSessionsResult(List<InsightClient.InsightSessionSummary> sessions) {}

  public record RecentSessionsInput(List<String> types, Integer limit) {}

  public record FetchSummaryInput(UUID sessionId, String type, String channel) {}

  public record SearchMemoryInput(
      UUID sessionId, String type, String query, String channel, Integer limit) {}

  public record FetchMetricsInput(UUID sessionId, String type) {}
}
