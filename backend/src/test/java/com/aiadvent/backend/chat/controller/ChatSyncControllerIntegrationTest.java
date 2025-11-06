package com.aiadvent.backend.chat.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aiadvent.backend.chat.api.ChatStreamRequestOptions;
import com.aiadvent.backend.chat.api.ChatSyncRequest;
import com.aiadvent.backend.chat.api.ChatSyncResponse;
import com.aiadvent.backend.chat.domain.ChatMessage;
import com.aiadvent.backend.chat.domain.ChatRole;
import com.aiadvent.backend.chat.domain.ChatSession;
import com.aiadvent.backend.chat.persistence.ChatMessageRepository;
import com.aiadvent.backend.chat.persistence.ChatSessionRepository;
import com.aiadvent.backend.chat.support.StubChatClientConfiguration;
import com.aiadvent.backend.chat.support.StubChatClientState;
import com.aiadvent.backend.support.PostgresTestContainer;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
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
  void syncPersistsPlainResponse() throws Exception {
    StubChatClientState.setSyncResponses(List.of("Plain assistant response."));
    StubChatClientState.setUsage(12, 24, 36);

    ChatSyncRequest request =
        new ChatSyncRequest(null, "Provide a quick answer", null, null, null, null);

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

    ChatSyncResponse response =
        objectMapper.readValue(result.getResponse().getContentAsString(), ChatSyncResponse.class);

    assertThat(response.content()).isEqualTo("Plain assistant response.");
    assertThat(response.usage()).isNotNull();
    assertThat(response.usage().promptTokens()).isEqualTo(12);
    assertThat(response.cost()).isNotNull();
    assertThat(response.cost().total()).isZero();
    assertThat(StubChatClientState.lastSyncMode()).isEqualTo("plain");

    UUID sessionId = UUID.fromString(result.getResponse().getHeader("X-Session-Id"));
    ChatSession session =
        chatSessionRepository
            .findById(sessionId)
            .orElseThrow(() -> new IllegalStateException("Session not found"));
    List<ChatMessage> messages =
        chatMessageRepository.findBySessionOrderBySequenceNumberAsc(session);

    assertThat(messages).hasSize(2);

    ChatMessage assistantMessage = messages.get(1);
    assertThat(assistantMessage.getRole()).isEqualTo(ChatRole.ASSISTANT);
    assertThat(assistantMessage.getContent()).isEqualTo("Plain assistant response.");
    assertThat(assistantMessage.getStructuredPayload().isEmpty()).isTrue();
  }

  @Test
  void syncAppliesSamplingOverrides() throws Exception {
    StubChatClientState.setSyncResponses(List.of("Overrides applied"));

    ChatSyncRequest request =
        new ChatSyncRequest(
            null,
            "Tune sampling for sync call",
            null,
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
    StubChatClientState.setSyncResponses(List.of("Default sampling"));

    ChatSyncRequest request =
        new ChatSyncRequest(null, "Use provider defaults", null, null, null, null);

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
  void syncHonorsRequestedMcpToolsInResearchMode() throws Exception {
    StubChatClientState.setSyncResponses(List.of("Research answer with MCP tools."));
    StubChatClientState.setUsage(50, 120, 170);

    ChatSyncRequest request =
        new ChatSyncRequest(
            null,
            "Расскажи о последних обновлениях платформы.",
            "openai",
            "gpt-4o-mini",
            "research",
            List.of("perplexity_search"),
            null,
            null);

    MvcResult result =
        mockMvc
            .perform(
                post(SYNC_ENDPOINT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isOk())
            .andExpect(header().exists("X-Session-Id"))
            .andExpect(header().string("X-New-Session", "true"))
            .andReturn();

    ChatSyncResponse response =
        objectMapper.readValue(result.getResponse().getContentAsString(), ChatSyncResponse.class);

    assertThat(response.content()).contains("Research answer");
    assertThat(response.tools()).containsExactly("perplexity_search");
    assertThat(response.usage()).isNotNull();
    assertThat(response.usage().promptTokens()).isEqualTo(50);
    assertThat(response.usage().completionTokens()).isEqualTo(120);
  }

  @Test
  void syncDoesNotFallbackToDefaultMcpToolsWhenNoneRequested() throws Exception {
    StubChatClientState.setSyncResponses(List.of("Research answer without MCP tools."));
    StubChatClientState.setUsage(45, 90, 135);

    ChatSyncRequest request =
        new ChatSyncRequest(
            null,
            "Расскажи об архитектуре проекта.",
            "openai",
            "gpt-4o-mini",
            "research",
            List.of(),
            null,
            null);

    MvcResult result =
        mockMvc
            .perform(
                post(SYNC_ENDPOINT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isOk())
            .andExpect(header().exists("X-Session-Id"))
            .andReturn();

    ChatSyncResponse response =
        objectMapper.readValue(result.getResponse().getContentAsString(), ChatSyncResponse.class);

    assertThat(response.tools()).isNull();
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

    StubChatClientState.setSyncResponses(List.of(tooManyRequests, "Recovered response"));

    ChatSyncRequest request =
        new ChatSyncRequest(null, "Provide resilient summary", null, null, null, null);

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
    assertThat(messages.get(1).getStructuredPayload().isEmpty()).isTrue();
    assertThat(StubChatClientState.syncCallCount()).isEqualTo(2);
  }

  @Test
  void syncRejectsEmptyMessage() throws Exception {
    ChatSyncRequest request = new ChatSyncRequest(null, "   ", null, null, null, null);

    mockMvc
        .perform(
            post(SYNC_ENDPOINT)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isBadRequest());
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
        new ChatSyncRequest(null, "Simulate upstream failure", null, null, null, null);

    mockMvc
        .perform(
            post(SYNC_ENDPOINT)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isBadGateway());

    assertThat(StubChatClientState.syncCallCount()).isGreaterThanOrEqualTo(3);
  }
}
