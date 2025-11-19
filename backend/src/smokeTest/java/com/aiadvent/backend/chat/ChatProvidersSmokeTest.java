package com.aiadvent.backend.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.aiadvent.backend.chat.api.ChatStreamEvent;
import com.aiadvent.backend.chat.api.ChatStreamRequest;
import com.aiadvent.backend.chat.persistence.ChatMessageRepository;
import com.aiadvent.backend.chat.persistence.ChatSessionRepository;
import com.aiadvent.backend.chat.support.StubChatClientConfiguration;
import com.aiadvent.backend.chat.support.StubChatClientState;
import com.aiadvent.backend.support.PostgresTestContainer;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.FluxExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.ai.openai.api-key=test-token",
      "spring.ai.openai.base-url=http://localhost",
      "app.chat.memory.window-size=2",
      "app.chat.memory.retention=PT1H",
      "app.chat.memory.cleanup-interval=PT30M"
    })
@AutoConfigureWebTestClient
@Import(StubChatClientConfiguration.class)
@ActiveProfiles("test")
class ChatProvidersSmokeTest extends PostgresTestContainer {

  private static final String PROFILE_KEY_HEADER = "X-Profile-Key";
  private static final String PROFILE_CHANNEL_HEADER = "X-Profile-Channel";
  private static final String PROFILE_KEY = "smoke:default";
  private static final String PROFILE_CHANNEL = "web";

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
  void smokeCoversBothProviders() {
    runSmokeFor("stub", "stub-model");
    runSmokeFor("alternate", "alt-model-pro");
  }

  private void runSmokeFor(String provider, String model) {
    StubChatClientState.setTokens(List.of(provider + "-" + model));

    FluxExchangeResult<ChatStreamEvent> result =
        webTestClient
            .post()
            .uri("/api/llm/chat/stream")
            .header(PROFILE_KEY_HEADER, PROFILE_KEY)
            .header(PROFILE_CHANNEL_HEADER, PROFILE_CHANNEL)
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.TEXT_EVENT_STREAM)
            .bodyValue(new ChatStreamRequest(null, "smoke check", provider, model, null, null))
            .exchange()
            .expectStatus()
            .isOk()
            .returnResult(ChatStreamEvent.class);

    List<ChatStreamEvent> events =
        result.getResponseBody().collectList().block(Duration.ofSeconds(5));

    assertThat(events).isNotNull();
    assertThat(events).isNotEmpty();
    assertThat(events.getFirst().type()).isEqualTo("session");
    assertThat(events)
        .extracting(ChatStreamEvent::provider)
        .containsOnly(provider);
    assertThat(events).extracting(ChatStreamEvent::model).containsOnly(model);
  }
}
