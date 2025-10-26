package com.aiadvent.backend.chat.memory;

import static org.assertj.core.api.Assertions.assertThat;

import com.aiadvent.backend.chat.config.ChatMemoryProperties;
import com.aiadvent.backend.chat.domain.ChatMemorySummary;
import com.aiadvent.backend.chat.domain.ChatSession;
import com.aiadvent.backend.chat.persistence.ChatMemorySummaryRepository;
import com.aiadvent.backend.chat.persistence.ChatSessionRepository;
import com.aiadvent.backend.chat.provider.ChatProviderService;
import com.aiadvent.backend.chat.provider.model.ChatProviderSelection;
import com.aiadvent.backend.chat.provider.model.ChatRequestOverrides;
import com.aiadvent.backend.chat.token.TokenUsageEstimator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ChatMemorySummarizerServiceTest {

  @Mock private TokenUsageEstimator tokenUsageEstimator;
  @Mock private ChatMemorySummaryRepository summaryRepository;
  @Mock private ChatSessionRepository chatSessionRepository;
  @Mock private ChatProviderService chatProviderService;
  @Mock private ChatMemoryRepository chatMemoryRepository;

  private SimpleMeterRegistry meterRegistry;
  private ObjectMapper objectMapper;
  private ChatMemorySummarizerService service;

  @BeforeEach
  void setUp() {
    meterRegistry = new SimpleMeterRegistry();
    objectMapper = new ObjectMapper();
    ChatMemoryProperties properties = new ChatMemoryProperties();
    properties.getSummarization().setEnabled(true);
    properties.getSummarization().setModel("stub:model");
    service =
        new ChatMemorySummarizerService(
            properties,
            tokenUsageEstimator,
            summaryRepository,
            chatSessionRepository,
            chatProviderService,
            chatMemoryRepository,
            meterRegistry,
            objectMapper);
  }

  @AfterEach
  void tearDown() {
    service.shutdown();
    meterRegistry.close();
  }

  @Test
  void buildSummarisationPromptKeepsRoleOrder() {
    List<Message> messages =
        List.of(
            SystemMessage.builder().text("Context").build(),
            UserMessage.builder().text("Question").build(),
            AssistantMessage.builder().content("Answer").build());

    String prompt = service.buildSummarisationPrompt(messages);

    assertThat(prompt).contains("system: Context");
    assertThat(prompt).contains("user: Question");
    assertThat(prompt).contains("assistant: Answer");
    assertThat(prompt).contains("Сформируй краткое");
  }

  @Test
  void extractContentReturnsFirstNonEmptyGeneration() {
    ChatResponse response =
        new ChatResponse(
            List.of(
                new Generation(AssistantMessage.builder().content("first").build()),
                new Generation(AssistantMessage.builder().content("second").build())));

    assertThat(service.extractContent(response)).isEqualTo("first");
  }

  @Test
  void summarizeTranscriptSkipsBlankPrompt() {
    Optional<String> summary = service.summarizeTranscript(UUID.randomUUID(), "   ", "test");
    assertThat(summary).isEmpty();
  }

  @Test
  void resolveTailCountKeepsAtLeastOneMessageWhenWindowIsLargerThanTranscript() {
    assertThat(service.resolveTailCount(5)).isEqualTo(4);
  }

  @Test
  void resolveTailCountHonoursWindowWhenTranscriptIsLonger() {
    assertThat(service.resolveTailCount(64)).isEqualTo(20);
  }

  @Test
  void persistSummaryStoresEstimatedTokenCount() throws Exception {
    Mockito.when(tokenUsageEstimator.estimate(Mockito.any()))
        .thenReturn(new TokenUsageEstimator.Estimate(0, 42, 42, false, false));
    Mockito.when(summaryRepository.save(Mockito.any(ChatMemorySummary.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    ChatSession session = new ChatSession();
    session.setSummaryUntilOrder(0);

    ReflectionTestUtils.invokeMethod(service, "persistSummary", session, "Summary text", 3);

    ArgumentCaptor<ChatMemorySummary> captor = ArgumentCaptor.forClass(ChatMemorySummary.class);
    Mockito.verify(summaryRepository).save(captor.capture());
    assertThat(captor.getValue().getTokenCount()).isEqualTo(42L);
  }

  @Test
  void loadConversationSnapshotWaitsForAssistantTail() {
    UUID sessionId = UUID.randomUUID();
    List<Message> fallback =
        List.of(UserMessage.builder().text("Question").build());
    List<Message> pending =
        List.of(UserMessage.builder().text("Question").build());
    List<Message> ready =
        List.of(
            UserMessage.builder().text("Question").build(),
            AssistantMessage.builder().content("Answer").build());

    Mockito.when(chatMemoryRepository.findByConversationId(sessionId.toString()))
        .thenReturn(pending)
        .thenReturn(ready);

    @SuppressWarnings("unchecked")
    List<Message> resolved =
        (List<Message>)
            ReflectionTestUtils.invokeMethod(
                service, "loadConversationSnapshot", sessionId, fallback);

    assertThat(resolved).isEqualTo(ready);
  }

  @Test
  void loadConversationSnapshotFallsBackToProvidedMessagesWhenHistoryMissing() {
    UUID sessionId = UUID.randomUUID();
    List<Message> fallback =
        List.of(UserMessage.builder().text("Only user").build());

    Mockito.when(chatMemoryRepository.findByConversationId(sessionId.toString()))
        .thenReturn(List.of());

    @SuppressWarnings("unchecked")
    List<Message> resolved =
        (List<Message>)
            ReflectionTestUtils.invokeMethod(
                service, "loadConversationSnapshot", sessionId, fallback);

    assertThat(resolved).isEqualTo(fallback);
  }
}
