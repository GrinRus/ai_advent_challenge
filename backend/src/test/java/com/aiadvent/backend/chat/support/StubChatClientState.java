package com.aiadvent.backend.chat.support;

import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
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

  private static final AtomicReference<Queue<Object>> SYNC_RESPONSES =
      new AtomicReference<>(new ConcurrentLinkedQueue<>());

  private static final AtomicInteger SYNC_CALLS = new AtomicInteger();

  private StubChatClientState() {}

  public static void setTokens(List<String> tokens) {
    TOKENS.set(List.copyOf(tokens));
  }

  public static void setSyncResponses(List<Object> responses) {
    ConcurrentLinkedQueue<Object> queue = new ConcurrentLinkedQueue<>();
    queue.addAll(responses);
    SYNC_RESPONSES.set(queue);
  }

  static Object pollSyncResponse() {
    return SYNC_RESPONSES.get().poll();
  }

  public static void reset() {
    TOKENS.set(List.of("default"));
    LAST_PROMPT.set(null);
    SYNC_RESPONSES.set(new ConcurrentLinkedQueue<>());
    SYNC_CALLS.set(0);
  }

  public static void capturePrompt(Prompt prompt) {
    LAST_PROMPT.set(prompt);
  }

  public static Prompt lastPrompt() {
    return LAST_PROMPT.get();
  }

  public static List<String> currentTokens() {
    return TOKENS.get();
  }

  static Flux<ChatResponse> responseFlux() {
    return Flux.fromIterable(
        TOKENS.get().stream()
            .map(
                token ->
                    new ChatResponse(
                        List.of(new Generation(AssistantMessage.builder().content(token).build()))))
            .toList());
  }

  public static void incrementSyncCall() {
    SYNC_CALLS.incrementAndGet();
  }

  public static int syncCallCount() {
    return SYNC_CALLS.get();
  }
}
