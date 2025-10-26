package com.aiadvent.backend.chat.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;

import com.aiadvent.backend.chat.domain.ChatSession;
import com.aiadvent.backend.chat.memory.ChatMemorySummarizerService.PreflightResult;
import com.aiadvent.backend.chat.persistence.ChatMemorySummaryRepository;
import com.aiadvent.backend.chat.persistence.ChatSessionRepository;
import com.aiadvent.backend.chat.provider.model.ChatProviderSelection;
import com.aiadvent.backend.chat.memory.ChatSummarizationPreflightManager;
import com.aiadvent.backend.chat.token.TokenUsageEstimator;
import com.aiadvent.backend.chat.token.TokenUsageEstimator.Estimate;
import com.aiadvent.backend.support.PostgresTestContainer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(
    properties = {
      "app.chat.memory.window-size=4",
      "app.chat.memory.summarization.enabled=true",
      "app.chat.memory.summarization.trigger-token-limit=10",
      "app.chat.memory.summarization.target-token-count=5",
      "app.chat.memory.summarization.model=openai:gpt-4o-mini",
      "app.chat.memory.summarization.max-concurrent-summaries=1",
      "app.chat.memory.summarization.max-queue-size=2"
    })
class ChatSummarizationSmokeTest extends PostgresTestContainer {

  private static final String KEY_FACT = "Order 42 shipped";

  @SpyBean private ChatMemorySummarizerService summarizerService;
  @Autowired private ChatSummarizationPreflightManager preflightManager;
  @Autowired private ChatMemoryRepository chatMemoryRepository;
  @Autowired private ChatSessionRepository chatSessionRepository;
  @Autowired private ChatMemorySummaryRepository summaryRepository;

  @MockBean private TokenUsageEstimator tokenUsageEstimator;

  private final AtomicReference<String> capturedPrompt = new AtomicReference<>();

  @BeforeEach
  void setUp() {
    summaryRepository.deleteAll();
    chatSessionRepository.deleteAll();

    org.mockito.Mockito.when(tokenUsageEstimator.estimate(any()))
        .thenReturn(new Estimate(100, 0, 100, false, false));

    capturedPrompt.set(null);
    org.mockito.Mockito.doAnswer(
            invocation -> {
              capturedPrompt.set(invocation.getArgument(1));
              return java.util.Optional.of("Summary keeps " + KEY_FACT);
            })
        .when(summarizerService)
        .summarizeTranscript(any(UUID.class), any(String.class), any(String.class));
  }

  @Test
  void smokeTestSummarizationPipeline() throws Exception {
    ChatSession session = chatSessionRepository.save(new ChatSession());
    UUID sessionId = session.getId();
    String conversationId = sessionId.toString();

    int baseSize = buildLongHistory().size();
    chatMemoryRepository.saveAll(conversationId, buildLongHistory());
    int sizeAfterUser = appendMessage(conversationId, UserMessage.builder().text("Continue").build());
    assertThat(sizeAfterUser)
        .withFailMessage("sizeAfterUser=%s baseSize=%s", sizeAfterUser, baseSize)
        .isEqualTo(baseSize + 1);

    preflightManager.run(
        sessionId, new ChatProviderSelection("openai", "gpt-4o-mini"), "Continue", "smoke");

    int sizeAfterAssistant =
        appendMessage(
        conversationId,
        AssistantMessage.builder().content("Continuation acknowledged with summary context").build());
    assertThat(sizeAfterAssistant)
        .withFailMessage(
            "sizeAfterAssistant=%s sizeAfterUser=%s", sizeAfterAssistant, sizeAfterUser)
        .isEqualTo(sizeAfterUser + 1);

    var summaries = awaitSummary(sessionId);
    assertThat(summaries).hasSize(1);
    assertThat(summaries.get(0).getSummaryText()).contains(KEY_FACT);

    List<Message> resultHistory = chatMemoryRepository.findByConversationId(conversationId);
    assertThat(resultHistory).hasSizeLessThanOrEqualTo(5);
    assertThat(resultHistory.get(0).getMetadata()).containsEntry("summary", true);
    assertThat(resultHistory.get(0).getText()).contains(KEY_FACT);

    assertThat(capturedPrompt.get()).contains(KEY_FACT);
  }

  private List<Message> buildLongHistory() {
    return List.of(
        SystemMessage.builder().text("You are helpful").build(),
        UserMessage.builder().text("Initial greeting").build(),
        AssistantMessage.builder().content("Hello, how can I help?").build(),
        UserMessage.builder().text("Here is status " + KEY_FACT).build(),
        AssistantMessage.builder().content("Thanks for the detail").build(),
        UserMessage.builder().text("Add to backlog").build(),
        AssistantMessage.builder().content("Added to backlog").build(),
        UserMessage.builder().text("Latest update coming up").build(),
        AssistantMessage.builder().content("Latest update acknowledged").build(),
        UserMessage.builder().text("Latest update: final check").build(),
        AssistantMessage.builder().content("Latest update processed").build(),
        UserMessage.builder().text("Latest update please confirm").build(),
        AssistantMessage.builder().content("Latest update").build());
  }

  private List<com.aiadvent.backend.chat.domain.ChatMemorySummary> awaitSummary(UUID sessionId)
      throws InterruptedException {
    long deadline = System.currentTimeMillis() + Duration.ofSeconds(5).toMillis();
    while (System.currentTimeMillis() < deadline) {
      List<com.aiadvent.backend.chat.domain.ChatMemorySummary> rows =
          summaryRepository.findBySessionIdOrderBySourceStartOrder(sessionId);
      if (!rows.isEmpty()) {
        return rows;
      }
      Thread.sleep(50);
    }
    throw new AssertionError("Summary was not created within timeout");
  }

  private int appendMessage(String conversationId, Message message) {
    List<Message> existing = new ArrayList<>(chatMemoryRepository.findByConversationId(conversationId));
    existing.add(message);
    chatMemoryRepository.saveAll(conversationId, existing);
    return existing.size();
  }
}
