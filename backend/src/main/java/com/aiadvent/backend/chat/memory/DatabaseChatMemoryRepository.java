package com.aiadvent.backend.chat.memory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.lang.Nullable;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

@Slf4j
public class DatabaseChatMemoryRepository implements ChatMemoryRepository {

  private static final String SELECT_MESSAGE_SQL =
      """
      SELECT message_order, role, content, metadata
      FROM chat_memory_message
      WHERE session_id = :sessionId
      ORDER BY message_order ASC
      """;
  private static final String SELECT_SUMMARY_SQL =
      """
      SELECT source_start_order, source_end_order, summary_text, token_count, language, metadata
      FROM chat_memory_summary
      WHERE session_id = :sessionId
      ORDER BY source_start_order ASC
      """;

  private static final String DELETE_SQL =
      "DELETE FROM chat_memory_message WHERE session_id = :sessionId";

  private static final String INSERT_SQL =
      """
      INSERT INTO chat_memory_message
        (id, session_id, message_order, role, content, metadata, created_at)
      VALUES
        (:id, :sessionId, :messageOrder, :role, :content, :metadata, :createdAt)
      """;

  private static final String UPSERT_SESSION_SQL =
      "INSERT INTO chat_session (id) VALUES (:sessionId) ON CONFLICT (id) DO NOTHING";

  private static final String SELECT_IDS_SQL =
      "SELECT DISTINCT session_id FROM chat_memory_message";

  private final NamedParameterJdbcTemplate jdbcTemplate;
  private final ObjectMapper objectMapper;

  public DatabaseChatMemoryRepository(
      NamedParameterJdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
    this.jdbcTemplate = jdbcTemplate;
    this.objectMapper = objectMapper;
  }

  @Override
  public List<String> findConversationIds() {
    List<String> ids =
        jdbcTemplate.query(
        SELECT_IDS_SQL,
        Map.of(),
        (rs, rowNum) -> {
          UUID value = rs.getObject("session_id", UUID.class);
          return value != null ? value.toString() : null;
        });
    ids.removeIf(Objects::isNull);
    return ids;
  }

  @Override
  public List<Message> findByConversationId(String conversationId) {
    UUID sessionId = parseConversationId(conversationId);
    if (sessionId == null) {
      return List.of();
    }

    List<StoredMessage> storedMessages =
        jdbcTemplate.query(
            SELECT_MESSAGE_SQL,
            new MapSqlParameterSource("sessionId", sessionId),
            (rs, rowNum) -> new StoredMessage(rs.getInt("message_order"), mapMessage(rs)));

    List<SummaryRow> summaries = findSummaries(sessionId);

    int summarizedOrder =
        summaries.stream().mapToInt(SummaryRow::sourceEndOrder).max().orElse(0);

    List<Message> result = new ArrayList<>(summaries.size() + storedMessages.size());

    for (SummaryRow summary : summaries) {
      result.add(summary.asMessage());
    }

    storedMessages.stream()
        .filter(entry -> entry.messageOrder() > summarizedOrder)
        .map(StoredMessage::message)
        .forEach(result::add);

    return result;
  }

  @Override
  public void saveAll(String conversationId, List<Message> messages) {
    UUID sessionId = parseConversationId(conversationId);
    if (sessionId == null) {
      return;
    }

    jdbcTemplate.update(DELETE_SQL, new MapSqlParameterSource("sessionId", sessionId));

    if (CollectionUtils.isEmpty(messages)) {
      return;
    }

    ensureSessionExists(sessionId);

    List<SqlParameterSource> batch = new ArrayList<>(messages.size());
    int order = 1;
    for (Message message : messages) {
      batch.add(toSqlParameters(sessionId, message, order));
      order++;
    }

    jdbcTemplate.batchUpdate(INSERT_SQL, batch.toArray(new SqlParameterSource[0]));
  }

  @Override
  public void deleteByConversationId(String conversationId) {
    UUID sessionId = parseConversationId(conversationId);
    if (sessionId == null) {
      return;
    }
    jdbcTemplate.update(DELETE_SQL, new MapSqlParameterSource("sessionId", sessionId));
  }

  private Message mapMessage(ResultSet resultSet) throws SQLException {
    String role = resultSet.getString("role");
    String content = resultSet.getString("content");
    String metadataJson = resultSet.getString("metadata");

    Map<String, Object> metadata = deserializeMetadata(metadataJson);

    MessageType messageType = MessageType.valueOf(role);
    return switch (messageType) {
      case USER -> UserMessage.builder().text(content).metadata(metadata).build();
      case ASSISTANT ->
          AssistantMessage.builder().content(content).properties(metadata).build();
      case SYSTEM -> SystemMessage.builder().text(content).metadata(metadata).build();
      default -> {
        log.warn("Unsupported message type {} encountered in chat memory window", messageType);
        yield new SystemMessage(content);
      }
    };
  }

  private MapSqlParameterSource toSqlParameters(UUID sessionId, Message message, int order) {
    MapSqlParameterSource params = new MapSqlParameterSource();
    params.addValue("id", UUID.randomUUID());
    params.addValue("sessionId", sessionId);
    params.addValue("messageOrder", order);
    params.addValue("role", message.getMessageType().name());
    params.addValue("content", message.getText(), Types.VARCHAR);
    params.addValue("metadata", serializeMetadata(message.getMetadata()), Types.VARCHAR);
    params.addValue("createdAt", OffsetDateTime.now(ZoneOffset.UTC), Types.TIMESTAMP_WITH_TIMEZONE);
    return params;
  }

  private Map<String, Object> deserializeMetadata(@Nullable String metadataJson) {
    if (!StringUtils.hasText(metadataJson)) {
      return Collections.emptyMap();
    }
    try {
      return objectMapper.readValue(metadataJson, Map.class);
    } catch (JsonProcessingException exception) {
      log.warn("Failed to deserialize chat memory metadata", exception);
      return Collections.emptyMap();
    }
  }

  private String serializeMetadata(Map<String, Object> metadata) {
    if (metadata == null || metadata.isEmpty()) {
      return "{}";
    }
    try {
      return objectMapper.writeValueAsString(metadata);
    } catch (JsonProcessingException exception) {
      log.warn("Failed to serialize chat memory metadata", exception);
      return "{}";
    }
  }

  private void ensureSessionExists(UUID sessionId) {
    MapSqlParameterSource params = new MapSqlParameterSource("sessionId", sessionId);
    jdbcTemplate.update(UPSERT_SESSION_SQL, params);
  }

  private UUID parseConversationId(String conversationId) {
    try {
      return UUID.fromString(conversationId);
    } catch (IllegalArgumentException exception) {
      log.warn("Conversation id {} is not a valid UUID, skipping chat memory operation", conversationId);
      return null;
    }
  }

  private List<SummaryRow> findSummaries(UUID sessionId) {
    return jdbcTemplate.query(
        SELECT_SUMMARY_SQL,
        new MapSqlParameterSource("sessionId", sessionId),
        (rs, rowNum) ->
            new SummaryRow(
                rs.getInt("source_start_order"),
                rs.getInt("source_end_order"),
                rs.getString("summary_text"),
                rs.getObject("token_count") != null ? rs.getLong("token_count") : null,
                rs.getString("language"),
                deserializeMetadata(rs.getString("metadata"))));
  }

  private record StoredMessage(int messageOrder, Message message) {}

  private final class SummaryRow {
    private final int sourceStartOrder;
    private final int sourceEndOrder;
    private final String summaryText;
    private final Long tokenCount;
    private final String language;
    private final Map<String, Object> metadata;

    SummaryRow(
        int sourceStartOrder,
        int sourceEndOrder,
        String summaryText,
        Long tokenCount,
        String language,
        Map<String, Object> metadata) {
      this.sourceStartOrder = sourceStartOrder;
      this.sourceEndOrder = sourceEndOrder;
      this.summaryText = summaryText;
      this.tokenCount = tokenCount;
      this.language = language;
      this.metadata = metadata != null ? metadata : Collections.emptyMap();
    }

    int sourceEndOrder() {
      return sourceEndOrder;
    }

    Message asMessage() {
      Map<String, Object> summaryMetadata = new LinkedHashMap<>(metadata);
      summaryMetadata.put("summary", true);
      summaryMetadata.put("sourceStartOrder", sourceStartOrder);
      summaryMetadata.put("sourceEndOrder", sourceEndOrder);
      if (tokenCount != null) {
        summaryMetadata.put("tokenCount", tokenCount);
      }
      if (StringUtils.hasText(language)) {
        summaryMetadata.put("language", language);
      }
      return SystemMessage.builder().text(summaryText).metadata(summaryMetadata).build();
    }
  }
}
