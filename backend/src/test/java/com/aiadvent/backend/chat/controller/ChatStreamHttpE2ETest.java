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
import java.time.Duration;
import java.util.List;
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
      "spring.ai.openai.base-url=http://localhost"
    })
@AutoConfigureWebTestClient
@ActiveProfiles("test")
@Import(StubChatClientConfiguration.class)
class ChatStreamHttpE2ETest {

  @Autowired private WebTestClient webTestClient;

  @Autowired private ChatSessionRepository chatSessionRepository;

  @Autowired private ChatMessageRepository chatMessageRepository;

  @BeforeEach
  void setUp() {
    chatMessageRepository.deleteAll();
    chatSessionRepository.deleteAll();
    StubChatClientState.reset();
  }

  @AfterEach
  void tearDown() {
    StubChatClientState.reset();
  }

  @Test
  void streamOverHttpProducesSseAndPersistsConversation() {
    StubChatClientState.setTokens(List.of("via ", "http"));

    FluxExchangeResult<ChatStreamEvent> exchangeResult =
        webTestClient
            .post()
            .uri("/api/llm/chat/stream")
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.TEXT_EVENT_STREAM)
            .bodyValue(new ChatStreamRequest(null, "Hello over HTTP"))
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
  }
}
