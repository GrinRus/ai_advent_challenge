package com.aiadvent.backend.chat.controller;

import com.aiadvent.backend.chat.api.ChatStreamEvent;
import com.aiadvent.backend.chat.api.ChatStreamRequest;
import com.aiadvent.backend.chat.provider.ChatProviderService;
import com.aiadvent.backend.chat.provider.model.ChatProviderSelection;
import com.aiadvent.backend.chat.provider.model.ChatRequestOverrides;
import com.aiadvent.backend.chat.api.ChatStreamRequestOptions;
import com.aiadvent.backend.chat.api.StructuredSyncUsageStats;
import com.aiadvent.backend.chat.api.UsageCostDetails;
import com.aiadvent.backend.chat.service.ChatService;
import com.aiadvent.backend.chat.service.ConversationContext;
import com.aiadvent.backend.chat.provider.model.UsageCostEstimate;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ResponseStatusException;
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

    if (!chatProviderService.supportsStreaming(selection)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "Model '" + selection.modelId() + "' does not support streaming responses.");
    }

    ConversationContext context =
        chatService.registerUserMessage(
            request.sessionId(), request.message(), selection.providerId(), selection.modelId());

    SseEmitter emitter = new SseEmitter(0L);
    AtomicReference<Usage> usageRef = new AtomicReference<>();

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
                    selection,
                    assistantResponse,
                    usageRef,
                    emitter),
            error -> handleError(error, context.sessionId(), selection, emitter),
            () ->
                handleCompletion(
                    context.sessionId(),
                    selection,
                    assistantResponse,
                    usageRef,
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
      ChatProviderSelection selection,
      StringBuilder assistantResponse,
      AtomicReference<Usage> usageRef,
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
                  ChatStreamEvent.token(sessionId, content, selection.providerId(), selection.modelId()));
            });

    ChatResponseMetadata metadata = response.getMetadata();
    if (metadata != null && metadata.getUsage() != null) {
      usageRef.set(metadata.getUsage());
    }
  }

  private void handleCompletion(
      UUID sessionId,
      ChatProviderSelection selection,
      StringBuilder assistantResponse,
      AtomicReference<Usage> usageRef,
      SseEmitter emitter) {
    String content = assistantResponse.toString();
    Usage usage = usageRef.get();
    UsageCostEstimate usageCost = chatProviderService.estimateUsageCost(selection, usage);
    chatService.registerAssistantMessage(
        sessionId, content, selection.providerId(), selection.modelId(), null, usageCost);
    emit(
        emitter,
        "complete",
        ChatStreamEvent.complete(
            sessionId,
            content,
            selection.providerId(),
            selection.modelId(),
            toUsageStats(usageCost),
            toCostDetails(usageCost)));
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

  private StructuredSyncUsageStats toUsageStats(UsageCostEstimate usageCost) {
    if (usageCost == null || !usageCost.hasUsage()) {
      return null;
    }
    return new StructuredSyncUsageStats(
        usageCost.promptTokens(), usageCost.completionTokens(), usageCost.totalTokens());
  }

  private UsageCostDetails toCostDetails(UsageCostEstimate usageCost) {
    if (usageCost == null || !usageCost.hasCost()) {
      return null;
    }
    return new UsageCostDetails(
        usageCost.inputCost(), usageCost.outputCost(), usageCost.totalCost(), usageCost.currency());
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
