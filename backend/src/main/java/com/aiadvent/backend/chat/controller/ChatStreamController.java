package com.aiadvent.backend.chat.controller;

import com.aiadvent.backend.chat.api.ChatStreamEvent;
import com.aiadvent.backend.chat.api.ChatStreamRequest;
import com.aiadvent.backend.chat.provider.ChatProviderService;
import com.aiadvent.backend.chat.provider.model.ChatProviderSelection;
import com.aiadvent.backend.chat.provider.model.ChatRequestOverrides;
import com.aiadvent.backend.chat.api.ChatStreamRequestOptions;
import com.aiadvent.backend.chat.service.ChatService;
import com.aiadvent.backend.chat.service.ConversationContext;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/api/llm/chat")
@Validated
@Slf4j
public class ChatStreamController {

  private final ChatService chatService;
  private final ChatProviderService chatProviderService;

  public ChatStreamController(ChatService chatService, ChatProviderService chatProviderService) {
    this.chatService = chatService;
    this.chatProviderService = chatProviderService;
  }

  @PostMapping(
      value = "/stream",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter stream(@RequestBody @Valid ChatStreamRequest request) {
    ChatProviderSelection selection =
        chatProviderService.resolveSelection(request.provider(), request.model());

    ConversationContext context =
        chatService.registerUserMessage(
            request.sessionId(), request.message(), selection.providerId(), selection.modelId());

    SseEmitter emitter = new SseEmitter(0L);

    emit(
        emitter,
        "session",
        ChatStreamEvent.session(
            context.sessionId(), context.newSession(), selection.providerId(), selection.modelId()));

    StringBuilder assistantResponse = new StringBuilder();
    ChatOptions chatOptions =
        chatProviderService.buildOptions(selection, resolveOverrides(request.options()));

    Flux<ChatResponse> responseFlux =
        chatProviderService
            .chatClient(selection.providerId())
            .prompt()
            .user(request.message())
            .advisors(
                advisors ->
                    advisors.param(ChatMemory.CONVERSATION_ID, context.sessionId().toString()))
            .options(chatOptions)
            .stream()
            .chatResponse();

    AtomicReference<Disposable> subscriptionRef = new AtomicReference<>();

    Disposable subscription =
        responseFlux.subscribe(
            response ->
                handleChunk(
                    response,
                    context.sessionId(),
                    selection.providerId(),
                    selection.modelId(),
                    assistantResponse,
                    emitter),
            error -> handleError(error, context.sessionId(), selection, emitter),
            () ->
                handleCompletion(
                    context.sessionId(),
                    selection.providerId(),
                    selection.modelId(),
                    assistantResponse,
                    emitter));

    subscriptionRef.set(subscription);

    emitter.onCompletion(() -> disposeSubscription(subscriptionRef));
    emitter.onTimeout(
        () -> {
          disposeSubscription(subscriptionRef);
          emit(
              emitter,
              "error",
              ChatStreamEvent.error(
                  context.sessionId(),
                  "Stream timeout reached, closing connection.",
                  selection.providerId(),
                  selection.modelId()));
          emitter.complete();
        });

    return emitter;
  }

  private void handleChunk(
      ChatResponse response,
      UUID sessionId,
      String providerId,
      String modelId,
      StringBuilder assistantResponse,
      SseEmitter emitter) {
    response
        .getResults()
        .forEach(
            generation -> {
              String content = generation.getOutput().getText();
              if (!StringUtils.hasText(content)) {
                return;
              }

              assistantResponse.append(content);
              if (log.isDebugEnabled()) {
                log.debug("Stream chunk for session {}: {}", sessionId, content);
              }
              emit(
                  emitter,
                  "token",
                  ChatStreamEvent.token(sessionId, content, providerId, modelId));
            });
  }

  private void handleCompletion(
      UUID sessionId,
      String providerId,
      String modelId,
      StringBuilder assistantResponse,
      SseEmitter emitter) {
    String content = assistantResponse.toString();
    chatService.registerAssistantMessage(sessionId, content, providerId, modelId);
    emit(
        emitter,
        "complete",
        ChatStreamEvent.complete(sessionId, content, providerId, modelId));
    emitter.complete();
  }

  private void handleError(
      Throwable error, UUID sessionId, ChatProviderSelection selection, SseEmitter emitter) {
    String message = buildErrorMessage(error);
    logError(sessionId, error);
    emit(
        emitter,
        "error",
        ChatStreamEvent.error(sessionId, message, selection.providerId(), selection.modelId()));
    emitter.complete();
  }

  private void emit(SseEmitter emitter, String eventName, ChatStreamEvent payload) {
    try {
      emitter.send(SseEmitter.event().name(eventName).data(payload));
    } catch (IOException ioException) {
      log.error(
          "Failed to send SSE event {} for session {}",
          eventName,
          payload.sessionId(),
          ioException);
      emitter.completeWithError(ioException);
    }
  }

  private void disposeSubscription(AtomicReference<Disposable> subscriptionRef) {
    Disposable disposable = subscriptionRef.getAndSet(null);
    if (disposable != null && !disposable.isDisposed()) {
      disposable.dispose();
    }
  }

  private void logError(UUID sessionId, Throwable error) {
    if (error instanceof WebClientResponseException webClientError) {
      log.error(
          "LLM streaming failed for session {} with status {} and body: {}",
          sessionId,
          webClientError.getStatusCode(),
          sanitize(webClientError.getResponseBodyAsString()),
          webClientError);
    } else {
      log.error("LLM streaming failed for session {}", sessionId, error);
    }
  }

  private String buildErrorMessage(Throwable error) {
    if (error instanceof WebClientResponseException webClientError) {
      String status = webClientError.getStatusCode().value() + " " + webClientError.getStatusText();
      String body = sanitize(webClientError.getResponseBodyAsString());

      if (StringUtils.hasText(body)) {
        return "Failed to stream response from model: " + status + " - " + body;
      }
      return "Failed to stream response from model: " + status;
    }

    String message = error.getMessage();
    if (!StringUtils.hasText(message)) {
      message = error.getClass().getSimpleName();
    }
    return "Failed to stream response from model: " + message;
  }

  private String sanitize(String value) {
    if (!StringUtils.hasText(value)) {
      return null;
    }

    String normalized = value.replaceAll("\\s+", " ").trim();
    int maxLength = 500;
    if (normalized.length() <= maxLength) {
      return normalized;
    }
    return normalized.substring(0, maxLength) + "...";
  }

  private ChatRequestOverrides resolveOverrides(ChatStreamRequestOptions options) {
    if (options == null) {
      return ChatRequestOverrides.empty();
    }
    return new ChatRequestOverrides(options.temperature(), options.topP(), options.maxTokens());
  }
}
