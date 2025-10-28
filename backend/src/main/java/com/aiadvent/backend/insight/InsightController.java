package com.aiadvent.backend.insight;

import com.aiadvent.backend.insight.api.InsightMemorySearchResponse;
import com.aiadvent.backend.insight.api.InsightMetricsResponse;
import com.aiadvent.backend.insight.api.InsightSessionSummaryResponse;
import com.aiadvent.backend.insight.api.InsightSummaryResponse;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/insight")
public class InsightController {

  private final InsightService insightService;

  public InsightController(InsightService insightService) {
    this.insightService = insightService;
  }

  @GetMapping("/sessions/recent")
  public List<InsightSessionSummaryResponse> recentSessions(
      @RequestParam(name = "type", required = false) List<String> types,
      @RequestParam(name = "limit", required = false) Integer limit) {
    Set<InsightSessionType> resolvedTypes = resolveTypes(types);
    int requestedLimit = limit != null ? limit : 0;
    return insightService.recentSessions(resolvedTypes, requestedLimit);
  }

  @GetMapping("/sessions/{sessionId}/summary")
  public InsightSummaryResponse fetchSummary(
      @PathVariable UUID sessionId,
      @RequestParam(name = "type") String type,
      @RequestParam(name = "channel", required = false) String channel) {
    InsightSessionType sessionType = InsightSessionType.fromString(type);
    return insightService.fetchSummary(sessionType, sessionId, channel);
  }

  @GetMapping("/sessions/{sessionId}/memory/search")
  public InsightMemorySearchResponse searchMemory(
      @PathVariable UUID sessionId,
      @RequestParam(name = "type") String type,
      @RequestParam(name = "q") String query,
      @RequestParam(name = "channel", required = false) String channel,
      @RequestParam(name = "limit", required = false) Integer limit) {
    InsightSessionType sessionType = InsightSessionType.fromString(type);
    int requestedLimit = limit != null ? limit : 0;
    return insightService.searchMemory(sessionType, sessionId, query, channel, requestedLimit);
  }

  @GetMapping("/sessions/{sessionId}/metrics")
  public InsightMetricsResponse metrics(
      @PathVariable UUID sessionId, @RequestParam(name = "type") String type) {
    InsightSessionType sessionType = InsightSessionType.fromString(type);
    return insightService.fetchMetrics(sessionType, sessionId);
  }

  private Set<InsightSessionType> resolveTypes(List<String> values) {
    if (values == null || values.isEmpty()) {
      return Set.of();
    }
    return values.stream()
        .filter(value -> value != null && !value.isBlank())
        .map(InsightSessionType::fromString)
        .collect(Collectors.toCollection(HashSet::new));
  }
}
