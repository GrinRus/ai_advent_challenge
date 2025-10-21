package com.aiadvent.backend.chat.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aiadvent.backend.chat.domain.ChatMessage;
import com.aiadvent.backend.chat.domain.ChatRole;
import com.aiadvent.backend.chat.domain.ChatSession;
import com.aiadvent.backend.chat.persistence.ChatMessageRepository;
import com.aiadvent.backend.chat.persistence.ChatSessionRepository;
import com.aiadvent.backend.support.PostgresTestContainer;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ChatSessionUsageControllerIntegrationTest extends PostgresTestContainer {

  @Autowired private MockMvc mockMvc;

  @Autowired private ChatSessionRepository chatSessionRepository;

  @Autowired private ChatMessageRepository chatMessageRepository;

  @BeforeEach
  void cleanDatabase() {
    chatMessageRepository.deleteAll();
    chatSessionRepository.deleteAll();
  }

  @Test
  void usageReturns404ForMissingSession() throws Exception {
    mockMvc
        .perform(
            get("/api/llm/sessions/{id}/usage", UUID.randomUUID())
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isNotFound());
  }

  @Test
  void usageAggregatesTokensAndCost() throws Exception {
    ChatSession session = chatSessionRepository.save(new ChatSession());

    ChatMessage userMessage =
        new ChatMessage(session, ChatRole.USER, "Hello", 1, "openai", "gpt-4o-mini");
    chatMessageRepository.save(userMessage);

    ChatMessage assistantMessage =
        new ChatMessage(
            session, ChatRole.ASSISTANT, "Reply", 2, "openai", "gpt-4o-mini", null);
    assistantMessage.applyUsage(
        100, 150, 250, new BigDecimal("0.01500000"), new BigDecimal("0.03000000"), "USD");
    chatMessageRepository.save(assistantMessage);

    mockMvc
        .perform(get("/api/llm/sessions/{id}/usage", session.getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.sessionId").value(session.getId().toString()))
        .andExpect(jsonPath("$.messages[1].usage.totalTokens").value(250))
        .andExpect(jsonPath("$.messages[1].cost.total").value(0.045))
        .andExpect(jsonPath("$.totals.usage.totalTokens").value(250))
        .andExpect(jsonPath("$.totals.cost.total").value(0.045))
        .andExpect(jsonPath("$.totals.cost.currency").value("USD"));

    assertThat(chatMessageRepository.findBySessionOrderBySequenceNumberAsc(session)).hasSize(2);
  }
}
