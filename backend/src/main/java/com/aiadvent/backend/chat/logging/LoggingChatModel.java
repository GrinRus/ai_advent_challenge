package com.aiadvent.backend.chat.logging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

/**
 * Decorator for {@link ChatModel} that logs the final prompt (after all advisors)
 * reaching the underlying LLM implementation and, optionally, the resulting completion.
 */
public class LoggingChatModel implements ChatModel {

  private static final Logger log = LoggerFactory.getLogger(LoggingChatModel.class);

  private final ChatModel delegate;
  private final boolean logCompletion;

  public LoggingChatModel(ChatModel delegate, boolean logCompletion) {
    this.delegate = delegate;
    this.logCompletion = logCompletion;
  }

  @Override
  public ChatResponse call(Prompt prompt) {
    if (log.isDebugEnabled()) {
      log.debug("\n===== FINAL PROMPT >>> =====\n{}\n============================", prompt);
    }
    ChatResponse response = delegate.call(prompt);
    if (logCompletion && log.isDebugEnabled()) {
      Generation generation = response != null ? response.getResult() : null;
      if (generation != null && generation.getOutput() != null) {
        log.debug(
            "\n===== COMPLETION <<< =====\n{}\n==========================",
            generation.getOutput());
      }
    }
    return response;
  }

  @Override
  public Flux<ChatResponse> stream(Prompt prompt) {
    return delegate.stream(prompt);
  }
}
