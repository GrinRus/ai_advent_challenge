package com.aiadvent.backend.chat.memory;

import com.aiadvent.backend.chat.memory.ChatMemorySummarizerService.PreflightResult;
import com.aiadvent.backend.chat.provider.model.ChatProviderSelection;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@Slf4j
public class ChatSummarizationPreflightManager {

  private final ChatMemorySummarizerService summarizerService;

  public ChatSummarizationPreflightManager(ChatMemorySummarizerService summarizerService) {
    this.summarizerService = summarizerService;
  }

  public void run(UUID sessionId, ChatProviderSelection selection, String userPrompt, String source) {
    if (selection == null || sessionId == null) {
      return;
    }
    summarizerService
        .preflight(sessionId, selection.providerId(), selection.modelId(), userPrompt)
        .ifPresent(result -> handleResult(source, sessionId, selection, result));
  }

  private void handleResult(
      String source, UUID sessionId, ChatProviderSelection selection, PreflightResult result) {
    logDecision(source, sessionId, selection, result);
    summarizerService.processPreflightResult(result);
  }

  private void logDecision(
      String source, UUID sessionId, ChatProviderSelection selection, PreflightResult result) {
    String entrypoint = StringUtils.hasText(source) ? source : "llm-client";
    var decision = result.decision();
    if (decision.shouldSummarize()) {
      log.info(
          "[{}] Conversation {} exceeded token limit for provider {}:{} (estimated {} tokens, limit {}). Summarisation scheduled.",
          entrypoint,
          sessionId,
          selection.providerId(),
          selection.modelId(),
          decision.estimatedTokens(),
          decision.triggerTokenLimit());
    } else if (log.isDebugEnabled()) {
      log.debug(
          "[{}] Conversation {} within token limit for provider {}:{} (estimated {} tokens, limit {}).",
          entrypoint,
          sessionId,
          selection.providerId(),
          selection.modelId(),
          decision.estimatedTokens(),
          decision.triggerTokenLimit());
    }
  }
}
