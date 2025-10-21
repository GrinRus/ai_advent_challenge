package com.aiadvent.backend.chat.support;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.metadata.Usage;
import reactor.core.publisher.Flux;

public final class StubChatClientState {

  private static final AtomicReference<List<String>> TOKENS =
      new AtomicReference<>(List.of("default"));

  private static final AtomicReference<Prompt> LAST_PROMPT = new AtomicReference<>();

  private static final AtomicReference<Queue<Object>> SYNC_RESPONSES =
      new AtomicReference<>(new ConcurrentLinkedQueue<>());

  private static final AtomicInteger SYNC_CALLS = new AtomicInteger();

  private static final AtomicReference<Usage> USAGE = new AtomicReference<>();

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
    USAGE.set(null);
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

  public static void setUsage(Integer promptTokens, Integer completionTokens, Integer totalTokens) {
    if (promptTokens == null && completionTokens == null && totalTokens == null) {
      USAGE.set(null);
      return;
    }
    Integer resolvedTotal =
        totalTokens != null
            ? totalTokens
            : (promptTokens != null && completionTokens != null)
                ? promptTokens + completionTokens
                : null;
    USAGE.set(new DefaultUsage(promptTokens, completionTokens, resolvedTotal));
  }

  static Usage usage() {
    return USAGE.get();
  }

  static Flux<ChatResponse> responseFlux() {
    List<String> tokens = TOKENS.get();
    Usage usage = USAGE.get();
    List<ChatResponse> responses = new ArrayList<>(tokens.size());
    for (int index = 0; index < tokens.size(); index++) {
      Generation generation =
          new Generation(AssistantMessage.builder().content(tokens.get(index)).build());
      boolean last = index == tokens.size() - 1;
      ChatResponseMetadata metadata =
          last && usage != null ? ChatResponseMetadata.builder().usage(usage).build() : null;
      responses.add(
          metadata != null ? new ChatResponse(List.of(generation), metadata) : new ChatResponse(List.of(generation)));
    }
    return Flux.fromIterable(responses);
  }

  public static void incrementSyncCall() {
    SYNC_CALLS.incrementAndGet();
  }

  public static int syncCallCount() {
    return SYNC_CALLS.get();
  }
}
