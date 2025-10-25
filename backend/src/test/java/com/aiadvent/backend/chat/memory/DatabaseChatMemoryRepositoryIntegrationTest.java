package com.aiadvent.backend.chat.memory;

import static org.assertj.core.api.Assertions.assertThat;

import com.aiadvent.backend.support.PostgresTestContainer;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
class DatabaseChatMemoryRepositoryIntegrationTest extends PostgresTestContainer {

  @Autowired private DatabaseChatMemoryRepository repository;
  @Autowired private NamedParameterJdbcTemplate jdbcTemplate;

  @BeforeEach
  void cleanTables() {
    jdbcTemplate.getJdbcTemplate().execute("TRUNCATE chat_memory_message CASCADE");
    jdbcTemplate.getJdbcTemplate().execute("TRUNCATE chat_memory_summary CASCADE");
    jdbcTemplate.getJdbcTemplate().execute("TRUNCATE chat_session CASCADE");
  }

  @Test
  @Transactional
  void findByConversationIdReturnsSummaryFollowedByTail() {
    UUID sessionId = UUID.randomUUID();
    insertChatSession(sessionId);
    insertSummary(sessionId, 1, 4, "Earlier summary");
    insertMessage(sessionId, 5, "USER", "hello");
    insertMessage(sessionId, 6, "ASSISTANT", "world");

    List<org.springframework.ai.chat.messages.Message> history =
        repository.findByConversationId(sessionId.toString());

    assertThat(history).hasSize(3);
    org.springframework.ai.chat.messages.Message summary = history.get(0);
    assertThat(summary.getMessageType().name()).isEqualTo("SYSTEM");
    assertThat(summary.getText()).isEqualTo("Earlier summary");
    assertThat(summary.getMetadata()).containsEntry("summary", true);

    assertThat(history.get(1).getMessageType().name()).isEqualTo("USER");
    assertThat(history.get(1).getText()).isEqualTo("hello");
    assertThat(history.get(2).getMessageType().name()).isEqualTo("ASSISTANT");
    assertThat(history.get(2).getText()).isEqualTo("world");
  }

  @Test
  @Transactional
  void saveAllPersistsMessagesInOrder() {
    UUID sessionId = UUID.randomUUID();
    org.springframework.ai.chat.messages.UserMessage userMessage =
        org.springframework.ai.chat.messages.UserMessage.builder().text("ping").build();
    org.springframework.ai.chat.messages.AssistantMessage assistantMessage =
        org.springframework.ai.chat.messages.AssistantMessage.builder().content("pong").build();

    repository.saveAll(sessionId.toString(), List.of(userMessage, assistantMessage));

    List<Map<String, Object>> rows =
        jdbcTemplate.queryForList(
            "SELECT role, content, message_order FROM chat_memory_message WHERE session_id = :sessionId ORDER BY message_order",
            new MapSqlParameterSource("sessionId", sessionId));

    assertThat(rows).hasSize(2);
    assertThat(rows.get(0)).containsEntry("role", "USER").containsEntry("message_order", 1);
    assertThat(rows.get(0).get("content")).isEqualTo("ping");
    assertThat(rows.get(1)).containsEntry("role", "ASSISTANT").containsEntry("message_order", 2);
    assertThat(rows.get(1).get("content")).isEqualTo("pong");
  }

  private void insertChatSession(UUID sessionId) {
    jdbcTemplate.update(
        "INSERT INTO chat_session (id, created_at) VALUES (:id, :createdAt)",
        new MapSqlParameterSource()
            .addValue("id", sessionId)
            .addValue("createdAt", OffsetDateTime.now(ZoneOffset.UTC)));
  }

  private void insertSummary(UUID sessionId, int startOrder, int endOrder, String text) {
    jdbcTemplate.update(
        "INSERT INTO chat_memory_summary (id, session_id, source_start_order, source_end_order, summary_text, created_at) "
            + "VALUES (:id, :sessionId, :startOrder, :endOrder, :text, :createdAt)",
        new MapSqlParameterSource()
            .addValue("id", UUID.randomUUID())
            .addValue("sessionId", sessionId)
            .addValue("startOrder", startOrder)
            .addValue("endOrder", endOrder)
            .addValue("text", text)
            .addValue("createdAt", OffsetDateTime.now(ZoneOffset.UTC)));
  }

  private void insertMessage(UUID sessionId, int order, String role, String content) {
    jdbcTemplate.update(
        "INSERT INTO chat_memory_message (id, session_id, message_order, role, content, metadata, created_at) "
            + "VALUES (:id, :sessionId, :order, :role, :content, :metadata, :createdAt)",
        new MapSqlParameterSource()
            .addValue("id", UUID.randomUUID())
            .addValue("sessionId", sessionId)
            .addValue("order", order)
            .addValue("role", role)
            .addValue("content", content)
            .addValue("metadata", "{}")
            .addValue("createdAt", OffsetDateTime.now(ZoneOffset.UTC)));
  }
}
