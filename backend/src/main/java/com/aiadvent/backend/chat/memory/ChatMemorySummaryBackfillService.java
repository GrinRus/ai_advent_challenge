package com.aiadvent.backend.chat.memory;

import com.aiadvent.backend.chat.config.ChatMemoryProperties;
import com.aiadvent.backend.chat.config.ChatMemoryProperties.BackfillProperties;
import com.aiadvent.backend.chat.memory.ChatMemorySummarizerService.SummarizerModelInfo;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Repeatable backfill utility that scans chat sessions with oversized memory windows and requests a
 * summary using the same worker that operates during runtime. The component is intentionally
 * idempotent: sessions that already have {@code summary_until_order} close to the latest
 * {@code chat_memory_message} entry are skipped automatically.
 */
@Service
@Slf4j
public class ChatMemorySummaryBackfillService {

  private static final String SELECT_CANDIDATES_SQL =
      """
      SELECT stats.session_id,
             stats.message_count,
             stats.last_order,
             COALESCE(cs.summary_until_order, 0) AS summary_until
      FROM (
        SELECT session_id,
               COUNT(*) AS message_count,
               MAX(message_order) AS last_order
        FROM chat_memory_message
        GROUP BY session_id
      ) stats
      JOIN chat_session cs ON cs.id = stats.session_id
      WHERE stats.message_count >= :minMessages
        AND (stats.last_order - COALESCE(cs.summary_until_order, 0)) >= :tailSize
      ORDER BY stats.last_order DESC
      LIMIT :limit
      """;

  private final ChatMemoryProperties properties;
  private final NamedParameterJdbcTemplate jdbcTemplate;
  private final ChatMemorySummarizerService summarizerService;

  public ChatMemorySummaryBackfillService(
      ChatMemoryProperties properties,
      NamedParameterJdbcTemplate jdbcTemplate,
      ChatMemorySummarizerService summarizerService) {
    this.properties = Objects.requireNonNull(properties, "properties must not be null");
    this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
    this.summarizerService =
        Objects.requireNonNull(summarizerService, "summarizerService must not be null");
  }

  /**
   * Executes a single backfill iteration. The caller is responsible for looping until this method
   * returns zero (no remaining candidates) or the desired number of sessions is processed.
   *
   * @return how many sessions were summarised during the iteration.
   */
  public int backfillBatch() {
    if (!summarizerService.isEnabled()) {
      log.debug("Chat memory summarisation is disabled, skipping backfill");
      return 0;
    }

    BackfillProperties config = properties.getSummarization().getBackfill();
    if (config == null || !config.isEnabled()) {
      log.debug("Chat memory summary backfill is disabled in configuration");
      return 0;
    }

    SummarizerModelInfo modelInfo =
        summarizerService
            .summarizerModel()
            .orElse(null);
    if (modelInfo == null) {
      log.warn("Summariser model is not configured, unable to run backfill");
      return 0;
    }

    List<CandidateSession> candidates = findCandidates(config);
    if (candidates.isEmpty()) {
      log.info("No chat sessions qualify for summary backfill");
      return 0;
    }

    int processed = 0;
    for (CandidateSession candidate : candidates) {
      try {
        summarizerService.forceSummarize(
            candidate.sessionId(), modelInfo.providerId(), modelInfo.modelId());
        processed++;
      } catch (Exception exception) {
        log.warn(
            "Failed to summarise chat session {} during backfill: {}",
            candidate.sessionId(),
            exception.getMessage());
      }
    }

    log.info("Backfill iteration completed: {} session(s) summarised", processed);
    return processed;
  }

  private List<CandidateSession> findCandidates(BackfillProperties config) {
    int minMessages = Math.max(1, config.getMinMessages());
    int limit = Math.max(1, config.getBatchSize());
    int tailSize = Math.max(4, properties.getWindowSize());

    MapSqlParameterSource params =
        new MapSqlParameterSource(Map.of("minMessages", minMessages, "tailSize", tailSize, "limit", limit));
    return jdbcTemplate.query(
        SELECT_CANDIDATES_SQL,
        params,
        (rs, rowNum) ->
            new CandidateSession(
                rs.getObject("session_id", UUID.class),
                rs.getInt("message_count"),
                rs.getInt("last_order"),
                rs.getInt("summary_until")));
  }

  record CandidateSession(UUID sessionId, int messageCount, int lastOrder, int summaryUntil) {}
}
