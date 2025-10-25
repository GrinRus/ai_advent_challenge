package com.aiadvent.backend.chat.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.aiadvent.backend.chat.memory.ChatMemorySummarizerService.PreflightResult;
import com.aiadvent.backend.chat.memory.ChatMemorySummarizerService.SummarizationDecision;
import com.aiadvent.backend.chat.provider.model.ChatProviderSelection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.slf4j.LoggerFactory;

class ChatSummarizationPreflightManagerTest {

  @Mock private ChatMemorySummarizerService summarizerService;

  private ChatSummarizationPreflightManager manager;
  private AutoCloseable mocks;

  @BeforeEach
  void setUp() {
    mocks = MockitoAnnotations.openMocks(this);
    manager = new ChatSummarizationPreflightManager(summarizerService);
  }

  @AfterEach
  void tearDown() throws Exception {
    if (mocks != null) {
      mocks.close();
    }
  }

  @Test
  void runDelegatesToSummarizer() {
    UUID sessionId = UUID.randomUUID();
    ChatProviderSelection selection = new ChatProviderSelection("openai", "gpt-4o-mini");
    PreflightResult result =
        new PreflightResult(
            sessionId,
            selection.providerId(),
            selection.modelId(),
            List.of(),
            new SummarizationDecision(true, 14000, 12000, 6000));

    when(summarizerService.preflight(eq(sessionId), eq("openai"), eq("gpt-4o-mini"), anyString()))
        .thenReturn(Optional.of(result));

    manager.run(sessionId, selection, "Test prompt", "sync-chat");

    verify(summarizerService).processPreflightResult(result);
  }

  @Test
  void runSkipsWhenSelectionMissing() {
    manager.run(UUID.randomUUID(), null, "prompt", "sync-chat");
    verify(summarizerService, never()).preflight(any(), anyString(), anyString(), anyString());
  }

  @Test
  void runLogsDetailsWhenSummarisationScheduled() {
    UUID sessionId = UUID.randomUUID();
    ChatProviderSelection selection = new ChatProviderSelection("openai", "gpt-4o-mini");
    PreflightResult result =
        new PreflightResult(
            sessionId,
            selection.providerId(),
            selection.modelId(),
            List.of(),
            new SummarizationDecision(true, 15000, 12000, 6000));

    when(summarizerService.preflight(eq(sessionId), eq("openai"), eq("gpt-4o-mini"), eq("prompt")))
        .thenReturn(Optional.of(result));

    Logger logger = (Logger) LoggerFactory.getLogger(ChatSummarizationPreflightManager.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);

    manager.run(sessionId, selection, "prompt", "test");

    assertThat(appender.list)
        .anySatisfy(
            event ->
                assertThat(event.getFormattedMessage())
                    .contains("exceeded token limit")
                    .contains(sessionId.toString())
                    .contains(selection.modelId()));
    logger.detachAppender(appender);
  }
}
