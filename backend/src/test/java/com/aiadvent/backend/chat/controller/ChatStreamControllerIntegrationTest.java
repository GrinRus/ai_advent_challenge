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
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.util.StringUtils;

@SpringBootTest(
    properties = {
      "spring.ai.openai.api-key=test-token",
      "spring.ai.openai.base-url=http://localhost"
    })
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(StubChatClientConfiguration.class)
class ChatStreamControllerIntegrationTest {

  @Autowired private MockMvc mockMvc;

  @Autowired private ObjectMapper objectMapper;

  @Autowired private ChatSessionRepository chatSessionRepository;

  @Autowired private ChatMessageRepository chatMessageRepository;

  @BeforeEach
  void cleanDatabase() {
    chatMessageRepository.deleteAll();
    chatSessionRepository.deleteAll();
    StubChatClientState.reset();
  }

  @AfterEach
  void cleanupState() {
    StubChatClientState.reset();
  }

  @Test
  void streamCreatesSessionAndPersistsHistory() throws Exception {
    StubChatClientState.setTokens(List.of("partial ", "answer"));

    ChatStreamRequest request = new ChatStreamRequest(null, "Hello model");

    MvcResult initialResult =
        mockMvc
            .perform(
                post("/api/llm/chat/stream")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
            .andExpect(request().asyncStarted())
            .andReturn();

    MvcResult completedResult =
        mockMvc
            .perform(asyncDispatch(initialResult))
            .andExpect(status().isOk())
            .andExpect(
                header()
                    .string("Content-Type", Matchers.startsWith(MediaType.TEXT_EVENT_STREAM_VALUE)))
            .andReturn();

    String responseBody = completedResult.getResponse().getContentAsString(StandardCharsets.UTF_8);

    List<SseFrame> events = parseSsePayload(responseBody);

    assertThat(events)
        .extracting(SseFrame::eventName)
        .containsExactly("session", "token", "token", "complete");

    List<ChatStreamEvent> payloads =
        events.stream().map(frame -> frame.event(objectMapper)).collect(Collectors.toList());

    assertThat(payloads)
        .first()
        .extracting(ChatStreamEvent::newSession, InstanceOfAssertFactories.BOOLEAN)
        .isTrue();

    assertThat(payloads.subList(1, payloads.size()))
        .extracting(ChatStreamEvent::sessionId)
        .containsOnly(payloads.get(0).sessionId());

    assertThat(payloads.get(1).content()).isEqualTo("partial ");
    assertThat(payloads.get(2).content()).isEqualTo("answer");
    assertThat(payloads.get(3).content()).isEqualTo("partial answer");

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
  }

  @Test
  void streamUsesExistingSessionHistory() throws Exception {
    ChatSession existingSession = chatSessionRepository.save(new ChatSession());
    chatMessageRepository.save(
        new ChatMessage(existingSession, ChatRole.USER, "Previous question", 1));
    chatMessageRepository.save(
        new ChatMessage(existingSession, ChatRole.ASSISTANT, "Previous answer", 2));

    StubChatClientState.setTokens(List.of("follow-up"));

    ChatStreamRequest request =
        new ChatStreamRequest(existingSession.getId(), "Next question from user");

    MvcResult initialResult =
        mockMvc
            .perform(
                post("/api/llm/chat/stream")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
            .andExpect(request().asyncStarted())
            .andReturn();

    mockMvc.perform(asyncDispatch(initialResult)).andExpect(status().isOk());

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
        chatMessageRepository.findBySessionOrderBySequenceNumberAsc(existingSession);
    assertThat(messages)
        .hasSize(4)
        .extracting(ChatMessage::getContent)
        .containsExactly(
            "Previous question", "Previous answer", "Next question from user", "follow-up");
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
}
