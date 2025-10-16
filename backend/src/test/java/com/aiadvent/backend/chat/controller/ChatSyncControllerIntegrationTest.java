package com.aiadvent.backend.chat.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aiadvent.backend.chat.api.ChatStreamRequestOptions;
import com.aiadvent.backend.chat.api.ChatSyncRequest;
import com.aiadvent.backend.chat.api.StructuredSyncResponse;
import com.aiadvent.backend.chat.domain.ChatMessage;
import com.aiadvent.backend.chat.domain.ChatRole;
import com.aiadvent.backend.chat.domain.ChatSession;
import com.aiadvent.backend.chat.persistence.ChatMessageRepository;
import com.aiadvent.backend.chat.persistence.ChatSessionRepository;
import com.aiadvent.backend.chat.support.StubChatClientConfiguration;
import com.aiadvent.backend.chat.support.StubChatClientState;
import com.aiadvent.backend.support.PostgresTestContainer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;

@SpringBootTest(
    properties = {
      "spring.ai.openai.api-key=test-token",
      "spring.ai.openai.base-url=http://localhost",
      "app.chat.memory.window-size=3",
      "app.chat.memory.retention=PT24H",
      "app.chat.memory.cleanup-interval=PT1H",
      "app.chat.default-provider=stub"
    })
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(StubChatClientConfiguration.class)
class ChatSyncControllerIntegrationTest extends PostgresTestContainer {

  private static final String SYNC_ENDPOINT = "/api/llm/chat/sync";

  @Autowired private MockMvc mockMvc;

  @Autowired private ObjectMapper objectMapper;

  @Autowired private ChatSessionRepository chatSessionRepository;

  @Autowired private ChatMessageRepository chatMessageRepository;

  @Autowired private JdbcTemplate jdbcTemplate;

  @BeforeEach
  void resetState() {
    chatMessageRepository.deleteAll();
    chatSessionRepository.deleteAll();
    jdbcTemplate.execute("DELETE FROM chat_memory_message");
    StubChatClientState.reset();
  }

  @AfterEach
  void cleanup() {
    StubChatClientState.reset();
  }

  @Test
  void syncPersistsStructuredPayload() throws Exception {
    String structuredJson =
        """
        {
          "requestId": "8f4e37f8-2c68-4f74-8c61-0f6be90485ce",
          "status": "success",
          "provider": {"type":"OPENAI","model":"stub-model"},
          "answer": {
            "summary": "Summarized response",
            "items": [
              {"title": "Key Action", "details": "Do something important", "tags": ["action"]}
            ],
            "confidence": 0.92
          },
          "usage": {"promptTokens": 10, "completionTokens": 20, "totalTokens": 30}
        }
        """;

    StubChatClientState.setSyncResponses(List.of(structuredJson));

    ChatSyncRequest request =
        new ChatSyncRequest(null, "Provide a structured summary", null, null, null);

    MvcResult result =
        mockMvc
            .perform(
                post(SYNC_ENDPOINT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isOk())
            .andExpect(header().exists("X-Session-Id"))
            .andExpect(header().exists("X-New-Session"))
            .andReturn();

    StructuredSyncResponse response =
        objectMapper.readValue(result.getResponse().getContentAsString(), StructuredSyncResponse.class);

    UUID sessionId = UUID.fromString(result.getResponse().getHeader("X-Session-Id"));
    ChatSession session =
        chatSessionRepository.findById(sessionId).orElseThrow(() -> new IllegalStateException("Session not found"));
    List<ChatMessage> messages =
        chatMessageRepository.findBySessionOrderBySequenceNumberAsc(session);

    assertThat(messages).hasSize(2);

    ChatMessage assistantMessage = messages.get(1);
    assertThat(assistantMessage.getRole()).isEqualTo(ChatRole.ASSISTANT);
    assertThat(assistantMessage.getStructuredPayload()).isNotNull();
    JsonNode payload = assistantMessage.getStructuredPayload();
    assertThat(payload.get("answer").get("items")).hasSize(1);
    assertThat(payload.get("provider").get("type").asText()).isEqualTo("OPENAI");

    assertThat(response.answer().summary()).isEqualTo("Summarized response");
    assertThat(StubChatClientState.syncCallCount()).isEqualTo(1);
  }

  @Test
  void syncAppliesSamplingOverrides() throws Exception {
    StubChatClientState.setSyncResponses(
        List.of("{\"answer\":{\"summary\":\"Overrides applied\",\"items\":[]}}"));

    ChatSyncRequest request =
        new ChatSyncRequest(
            null,
            "Tune sampling for structured call",
            null,
            null,
            new ChatStreamRequestOptions(0.3, 0.85, 640));

    mockMvc
        .perform(
            post(SYNC_ENDPOINT)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isOk());

    Prompt prompt = StubChatClientState.lastPrompt();
    assertThat(prompt).isNotNull();
    assertThat(prompt.getOptions()).isInstanceOf(OpenAiChatOptions.class);
    OpenAiChatOptions options = (OpenAiChatOptions) prompt.getOptions();
    assertThat(options.getTemperature()).isEqualTo(0.3);
    assertThat(options.getTopP()).isEqualTo(0.85);
    assertThat(options.getMaxTokens()).isEqualTo(640);
  }

  @Test
  void syncUsesProviderDefaultsWhenOverridesMissing() throws Exception {
    StubChatClientState.setSyncResponses(
        List.of("{\"answer\":{\"summary\":\"Default sampling\",\"items\":[]}}"));

    ChatSyncRequest request =
        new ChatSyncRequest(null, "Use provider defaults", null, null, null);

    mockMvc
        .perform(
            post(SYNC_ENDPOINT)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isOk());

    Prompt prompt = StubChatClientState.lastPrompt();
    assertThat(prompt).isNotNull();
    assertThat(prompt.getOptions()).isInstanceOf(OpenAiChatOptions.class);
    OpenAiChatOptions options = (OpenAiChatOptions) prompt.getOptions();
    assertThat(options.getTemperature()).isEqualTo(0.7);
    assertThat(options.getTopP()).isEqualTo(1.0);
    assertThat(options.getMaxTokens()).isEqualTo(1024);
  }

  @Test
  void syncRetriesOnConfiguredStatusAndSucceeds() throws Exception {
    WebClientResponseException tooManyRequests =
        WebClientResponseException.create(
            HttpStatus.TOO_MANY_REQUESTS.value(),
            "Too Many Requests",
            HttpHeaders.EMPTY,
            new byte[0],
            StandardCharsets.UTF_8);

    String structuredJson =
        """
        {"answer":{"summary":"Recovered","items":[]}}
        """;

    StubChatClientState.setSyncResponses(List.of(tooManyRequests, structuredJson));

    ChatSyncRequest request =
        new ChatSyncRequest(null, "Provide resilient summary", null, null, null);

    MvcResult result =
        mockMvc
            .perform(
                post(SYNC_ENDPOINT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isOk())
            .andReturn();

    UUID sessionId = UUID.fromString(result.getResponse().getHeader("X-Session-Id"));
    ChatSession session =
        chatSessionRepository.findById(sessionId).orElseThrow();
    List<ChatMessage> messages =
        chatMessageRepository.findBySessionOrderBySequenceNumberAsc(session);

    assertThat(messages).hasSize(2);
    assertThat(StubChatClientState.syncCallCount()).isEqualTo(2);
    assertThat(messages.get(1).getStructuredPayload()).isNotNull();
  }

  @Test
  void syncReturns422OnSchemaViolation() throws Exception {
    StubChatClientState.setSyncResponses(List.of("{\"unexpected\": true}"));

    ChatSyncRequest request =
        new ChatSyncRequest(null, "Return invalid structure", null, null, null);

    mockMvc
        .perform(
            post(SYNC_ENDPOINT)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isUnprocessableEntity());

    assertThat(chatSessionRepository.findAll()).isEmpty();
    assertThat(chatMessageRepository.findAll()).isEmpty();
  }

  @Test
  void syncPropagates502AfterRetriesExhausted() throws Exception {
    WebClientResponseException badGateway =
        WebClientResponseException.create(
            HttpStatus.BAD_GATEWAY.value(),
            "Bad Gateway",
            HttpHeaders.EMPTY,
            new byte[0],
            StandardCharsets.UTF_8);

    StubChatClientState.setSyncResponses(List.of(badGateway, badGateway, badGateway));

    ChatSyncRequest request =
        new ChatSyncRequest(null, "Simulate upstream failure", null, null, null);

    mockMvc
        .perform(
            post(SYNC_ENDPOINT)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isBadGateway());

    assertThat(StubChatClientState.syncCallCount()).isGreaterThanOrEqualTo(3);
  }
}
