package com.aiadvent.backend.chat.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.util.StringUtils;

@SpringBootTest(
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
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(StubChatClientConfiguration.class)
class ChatStreamControllerIntegrationTest extends PostgresTestContainer {

  @Autowired private MockMvc mockMvc;

  @Autowired private ObjectMapper objectMapper;

  @Autowired private ChatSessionRepository chatSessionRepository;

  @Autowired private ChatMessageRepository chatMessageRepository;

  @Autowired private JdbcTemplate jdbcTemplate;

  @BeforeEach
  void cleanDatabase() {
    chatMessageRepository.deleteAll();
    chatSessionRepository.deleteAll();
    jdbcTemplate.execute("DELETE FROM chat_memory_message");
    StubChatClientState.reset();
  }

  @AfterEach
  void cleanupState() {
    StubChatClientState.reset();
  }

  @Test
  void streamCreatesSessionAndPersistsHistory() throws Exception {
    StubChatClientState.setTokens(List.of("partial ", "answer"));

    ChatStreamRequest request = new ChatStreamRequest(null, "Hello model", null, null, null);

    List<ChatStreamEvent> events = performChatStream(request);

    assertThat(events)
        .extracting(ChatStreamEvent::type)
        .containsExactly("session", "token", "token", "complete");

    assertThat(events)
        .allSatisfy(
            event -> {
              assertThat(event.provider()).isEqualTo("stub");
              assertThat(event.model()).isEqualTo("stub-model");
            });

    assertThat(events)
        .first()
        .extracting(ChatStreamEvent::newSession, InstanceOfAssertFactories.BOOLEAN)
        .isTrue();

    assertThat(events.subList(1, events.size()))
        .extracting(ChatStreamEvent::sessionId)
        .containsOnly(events.get(0).sessionId());

    assertThat(events.get(1).content()).isEqualTo("partial ");
    assertThat(events.get(2).content()).isEqualTo("answer");
    assertThat(events.get(3).content()).isEqualTo("partial answer");

    List<ChatSession> sessions = chatSessionRepository.findAll();
    assertThat(sessions).hasSize(1);

    List<ChatMessage> messages =
        chatMessageRepository.findBySessionOrderBySequenceNumberAsc(sessions.getFirst());

    assertThat(messages)
        .hasSize(2)
        .extracting(ChatMessage::getRole)
        .containsExactly(ChatRole.USER, ChatRole.ASSISTANT);

    assertThat(messages.getFirst().getContent()).isEqualTo("Hello model");
    assertThat(messages.get(1).getContent()).isEqualTo("partial answer");
    assertThat(messages)
        .extracting(ChatMessage::getProvider)
        .containsOnly("stub");
    assertThat(messages)
        .extracting(ChatMessage::getModel)
        .containsOnly("stub-model");

    Integer memoryEntries =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM chat_memory_message WHERE session_id = ?",
            Integer.class,
            sessions.getFirst().getId());
    assertThat(memoryEntries).isEqualTo(2);
  }

  @Test
  void streamUsesChatMemoryWindowAcrossRequests() throws Exception {
    StubChatClientState.setTokens(List.of("Previous answer"));
    List<ChatStreamEvent> initialEvents =
        performChatStream(new ChatStreamRequest(null, "Previous question", null, null, null));

    UUID sessionId = initialEvents.getFirst().sessionId();
    ChatSession persistedSession = chatSessionRepository.findById(sessionId).orElseThrow();

    StubChatClientState.setTokens(List.of("follow-up"));

    performChatStream(new ChatStreamRequest(sessionId, "Next question from user", null, null, null));

    Prompt prompt = StubChatClientState.lastPrompt();
    assertThat(prompt).isNotNull();

    List<Message> instructions = prompt.getInstructions();
    assertThat(instructions).hasSize(3);
    assertThat(instructions.get(0))
        .isInstanceOf(UserMessage.class)
        .asInstanceOf(InstanceOfAssertFactories.type(UserMessage.class))
        .extracting(UserMessage::getText)
        .isEqualTo("Previous question");
    assertThat(instructions.get(1))
        .isInstanceOf(AssistantMessage.class)
        .asInstanceOf(InstanceOfAssertFactories.type(AssistantMessage.class))
        .extracting(AssistantMessage::getText)
        .isEqualTo("Previous answer");
    assertThat(instructions.get(2))
        .isInstanceOf(UserMessage.class)
        .asInstanceOf(InstanceOfAssertFactories.type(UserMessage.class))
        .extracting(UserMessage::getText)
        .isEqualTo("Next question from user");

    List<ChatMessage> messages =
        chatMessageRepository.findBySessionOrderBySequenceNumberAsc(persistedSession);
    assertThat(messages)
        .hasSize(4)
        .extracting(ChatMessage::getContent)
        .containsExactly(
            "Previous question", "Previous answer", "Next question from user", "follow-up");
    assertThat(messages)
        .extracting(ChatMessage::getProvider)
        .containsOnly("stub");
    assertThat(messages)
        .extracting(ChatMessage::getModel)
        .containsOnly("stub-model");

    List<String> memoryMessages =
        jdbcTemplate.query(
            "SELECT content FROM chat_memory_message WHERE session_id = ? ORDER BY message_order",
            (rs, rowNum) -> rs.getString("content"),
            sessionId);

    assertThat(memoryMessages).containsExactly("Previous answer", "Next question from user", "follow-up");
  }

  private List<SseFrame> parseSsePayload(String responseBody) {
    return Arrays.stream(responseBody.split("\n\n"))
        .filter(StringUtils::hasText)
        .map(String::trim)
        .map(
            block -> {
              String[] lines = block.split("\n");
              String eventName =
                  Arrays.stream(lines)
                      .filter(line -> line.startsWith("event:"))
                      .findFirst()
                      .map(line -> line.substring("event:".length()).trim())
                      .orElse("message");

              String dataLine =
                  Arrays.stream(lines)
                      .filter(line -> line.startsWith("data:"))
                      .findFirst()
                      .orElseThrow();

              String data = dataLine.substring("data:".length()).trim();
              return new SseFrame(eventName, data);
            })
        .collect(Collectors.toCollection(ArrayList::new));
  }

  private record SseFrame(String eventName, String data) {
    ChatStreamEvent event(ObjectMapper mapper) {
      try {
        return mapper.readValue(data, ChatStreamEvent.class);
      } catch (Exception exception) {
        throw new IllegalStateException("Failed to deserialize SSE payload", exception);
      }
    }
  }

  private List<ChatStreamEvent> performChatStream(ChatStreamRequest request) throws Exception {
    MvcResult initialResult =
        mockMvc
            .perform(
                post("/api/llm/chat/stream")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.TEXT_EVENT_STREAM)
                    .content(objectMapper.writeValueAsString(request)))
            .andExpect(request().asyncStarted())
            .andReturn();

    initialResult.getAsyncResult(Duration.ofSeconds(2).toMillis());

    MvcResult completedResult =
        mockMvc
            .perform(asyncDispatch(initialResult))
            .andExpect(status().isOk())
            .andExpect(
                header()
                    .string("Content-Type", Matchers.startsWith(MediaType.TEXT_EVENT_STREAM_VALUE)))
            .andReturn();

    String responseBody =
        completedResult.getResponse().getContentAsString(StandardCharsets.UTF_8);

    return parseSsePayload(responseBody).stream()
        .map(frame -> frame.event(objectMapper))
        .collect(Collectors.toList());
  }
}
