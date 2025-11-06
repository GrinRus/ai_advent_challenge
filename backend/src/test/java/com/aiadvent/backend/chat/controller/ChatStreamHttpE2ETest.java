package com.aiadvent.backend.chat.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.aiadvent.backend.chat.api.ChatStreamEvent;
import com.aiadvent.backend.chat.api.ChatStreamRequest;
import com.aiadvent.backend.chat.domain.ChatMessage;
import com.aiadvent.backend.chat.domain.ChatRole;
import com.aiadvent.backend.chat.domain.ChatSession;
import com.aiadvent.backend.chat.persistence.ChatMessageRepository;
import com.aiadvent.backend.chat.persistence.ChatSessionRepository;
import com.aiadvent.backend.chat.support.StubChatClientConfiguration;
import com.aiadvent.backend.chat.support.StubChatClientState;
import com.aiadvent.backend.support.PostgresTestContainer;
import java.time.Duration;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.FluxExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.ai.openai.api-key=test-token",
      "spring.ai.openai.base-url=http://localhost",
      "app.chat.memory.window-size=3",
      "app.chat.memory.retention=PT24H",
      "app.chat.memory.cleanup-interval=PT1H",
      "app.chat.default-provider=real",
      "app.chat.providers.real.type=OPENAI",
      "app.chat.providers.real.default-model=real-model",
      "app.chat.providers.real.temperature=0.7",
      "app.chat.providers.real.top-p=1.0",
      "app.chat.providers.real.max-tokens=1024"
    })
@AutoConfigureWebTestClient
@ActiveProfiles("test")
@Import(StubChatClientConfiguration.class)
class ChatStreamHttpE2ETest extends PostgresTestContainer {

  @Autowired private WebTestClient webTestClient;

  @Autowired private ChatSessionRepository chatSessionRepository;

  @Autowired private ChatMessageRepository chatMessageRepository;

  @Autowired private JdbcTemplate jdbcTemplate;

  @BeforeEach
  void setUp() {
    chatMessageRepository.deleteAll();
    chatSessionRepository.deleteAll();
    jdbcTemplate.execute("DELETE FROM chat_memory_message");
    StubChatClientState.reset();
  }

  @AfterEach
  void tearDown() {
    StubChatClientState.reset();
  }

  @Test
  void streamOverHttpProducesSseAndPersistsConversation() {
    StubChatClientState.setTokens(List.of("via ", "http"));
    StubChatClientState.setUsage(8, 12, 20);

    FluxExchangeResult<ChatStreamEvent> exchangeResult =
        webTestClient
            .post()
            .uri("/api/llm/chat/stream")
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.TEXT_EVENT_STREAM)
            .bodyValue(new ChatStreamRequest(null, "Hello over HTTP", null, null, null, null))
            .exchange()
            .expectStatus()
            .isOk()
            .expectHeader()
            .contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
            .returnResult(ChatStreamEvent.class);

    List<ChatStreamEvent> events =
        exchangeResult.getResponseBody().collectList().block(Duration.ofSeconds(5));

    assertThat(events).isNotNull();
    assertThat(events)
        .extracting(ChatStreamEvent::type)
        .containsExactly("session", "token", "token", "complete");

    ChatStreamEvent sessionEvent = events.getFirst();
    assertThat(sessionEvent.newSession()).isTrue();
    assertThat(sessionEvent.provider()).isEqualTo("stub");
    assertThat(sessionEvent.model()).isEqualTo("stub-model");

    List<ChatStreamEvent> subsequentEvents = events.subList(1, events.size());
    assertThat(subsequentEvents)
        .extracting(ChatStreamEvent::sessionId)
        .containsOnly(sessionEvent.sessionId());

    assertThat(subsequentEvents)
        .extracting(ChatStreamEvent::type, ChatStreamEvent::content)
        .containsSequence(
            org.assertj.core.api.Assertions.tuple("token", "via "),
            org.assertj.core.api.Assertions.tuple("token", "http"),
            org.assertj.core.api.Assertions.tuple("complete", "via http"));
    assertThat(subsequentEvents)
        .extracting(ChatStreamEvent::provider)
        .containsOnly("stub");
    assertThat(subsequentEvents)
        .extracting(ChatStreamEvent::model)
        .containsOnly("stub-model");
    assertThat(subsequentEvents.getLast().usageSource()).isEqualTo("native");

    ChatSession persistedSession =
        chatSessionRepository.findById(sessionEvent.sessionId()).orElseThrow();

    List<ChatMessage> history =
        chatMessageRepository.findBySessionOrderBySequenceNumberAsc(persistedSession);
    assertThat(history)
        .hasSize(2)
        .extracting(ChatMessage::getRole, ChatMessage::getContent)
        .containsExactly(
            org.assertj.core.api.Assertions.tuple(ChatRole.USER, "Hello over HTTP"),
            org.assertj.core.api.Assertions.tuple(ChatRole.ASSISTANT, "via http"));
    assertThat(history)
        .extracting(ChatMessage::getProvider)
        .containsOnly("stub");
    assertThat(history)
        .extracting(ChatMessage::getModel)
        .containsOnly("stub-model");

    Integer memoryEntries =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM chat_memory_message WHERE session_id = ?",
            Integer.class,
            sessionEvent.sessionId());
    assertThat(memoryEntries).isEqualTo(2);
  }

  @Test
  void streamSupportsCustomProviderAndModel() {
    StubChatClientState.setTokens(List.of("alternate ", "provider"));
    StubChatClientState.setUsage(null, null, null);

    FluxExchangeResult<ChatStreamEvent> exchangeResult =
        webTestClient
            .post()
            .uri("/api/llm/chat/stream")
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.TEXT_EVENT_STREAM)
            .bodyValue(
                new ChatStreamRequest(
                    null,
                    "Invoke alternate provider",
                    "alternate",
                    "alt-model-pro",
                    null,
                    null))
            .exchange()
            .expectStatus()
            .isOk()
            .expectHeader()
            .contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
            .returnResult(ChatStreamEvent.class);

    List<ChatStreamEvent> events =
        exchangeResult.getResponseBody().collectList().block(Duration.ofSeconds(5));

    assertThat(events).isNotNull();
    assertThat(events)
        .extracting(ChatStreamEvent::type)
        .containsExactly("session", "token", "token", "complete");

    ChatStreamEvent sessionEvent = events.getFirst();
    assertThat(sessionEvent.provider()).isEqualTo("alternate");
    assertThat(sessionEvent.model()).isEqualTo("alt-model-pro");

    List<ChatStreamEvent> responseEvents = events.subList(1, events.size());
    assertThat(responseEvents).extracting(ChatStreamEvent::provider).containsOnly("alternate");
    assertThat(responseEvents).extracting(ChatStreamEvent::model).containsOnly("alt-model-pro");
    assertThat(responseEvents.getLast().usageSource()).isEqualTo("fallback");

    ChatSession persistedSession =
        chatSessionRepository.findById(sessionEvent.sessionId()).orElseThrow();

    List<ChatMessage> history =
        chatMessageRepository.findBySessionOrderBySequenceNumberAsc(persistedSession);

    assertThat(history)
        .hasSize(2)
        .extracting(ChatMessage::getProvider)
        .containsOnly("alternate");
    assertThat(history)
        .extracting(ChatMessage::getModel)
        .containsOnly("alt-model-pro");
  }
}
