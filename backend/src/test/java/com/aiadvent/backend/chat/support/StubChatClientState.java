package com.aiadvent.backend.chat.support;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

public final class StubChatClientState {

  private static final AtomicReference<List<String>> TOKENS =
      new AtomicReference<>(List.of("default"));

  private static final AtomicReference<Prompt> LAST_PROMPT = new AtomicReference<>();

  private StubChatClientState() {}

  public static void setTokens(List<String> tokens) {
    TOKENS.set(List.copyOf(tokens));
  }

  public static void reset() {
    TOKENS.set(List.of("default"));
    LAST_PROMPT.set(null);
  }

  public static void capturePrompt(Prompt prompt) {
    LAST_PROMPT.set(prompt);
  }

  public static Prompt lastPrompt() {
    return LAST_PROMPT.get();
  }

  static Flux<ChatResponse> responseFlux() {
    return Flux.fromIterable(
        TOKENS.get().stream()
            .map(token -> new ChatResponse(List.of(new Generation(new AssistantMessage(token)))))
            .toList());
  }
}
