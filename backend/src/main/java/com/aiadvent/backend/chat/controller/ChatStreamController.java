package com.aiadvent.backend.chat.controller;

import com.aiadvent.backend.chat.api.ChatStreamEvent;
import com.aiadvent.backend.chat.api.ChatStreamRequest;
import com.aiadvent.backend.chat.domain.ChatRole;
import com.aiadvent.backend.chat.service.ChatService;
import com.aiadvent.backend.chat.service.ConversationContext;
import com.aiadvent.backend.chat.service.ConversationMessage;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
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
  private final ChatClient chatClient;

  public ChatStreamController(ChatService chatService, ChatClient.Builder chatClientBuilder) {
    this.chatService = chatService;
    this.chatClient = chatClientBuilder.build();
  }

  @PostMapping(
      value = "/stream",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter stream(@RequestBody @Valid ChatStreamRequest request) {
    ConversationContext context =
        chatService.registerUserMessage(request.sessionId(), request.message());

    List<Message> promptMessages = context.history().stream().map(this::toAiMessage).toList();

    Prompt prompt = new Prompt(promptMessages);
    SseEmitter emitter = new SseEmitter(0L);

    emit(emitter, "session", ChatStreamEvent.session(context.sessionId(), context.newSession()));

    StringBuilder assistantResponse = new StringBuilder();
    Flux<ChatResponse> responseFlux = chatClient.prompt(prompt).stream().chatResponse();

    AtomicReference<Disposable> subscriptionRef = new AtomicReference<>();

    Disposable subscription =
        responseFlux.subscribe(
            response -> handleChunk(response, context.sessionId(), assistantResponse, emitter),
            error -> handleError(error, context.sessionId(), emitter),
            () -> handleCompletion(context.sessionId(), assistantResponse, emitter));

    subscriptionRef.set(subscription);

    emitter.onCompletion(() -> disposeSubscription(subscriptionRef));
    emitter.onTimeout(
        () -> {
          disposeSubscription(subscriptionRef);
          emit(
              emitter,
              "error",
              ChatStreamEvent.error(
                  context.sessionId(), "Stream timeout reached, closing connection."));
          emitter.complete();
        });

    return emitter;
  }

  private void handleChunk(
      ChatResponse response, UUID sessionId, StringBuilder assistantResponse, SseEmitter emitter) {
    response
        .getResults()
        .forEach(
            generation -> {
              String content = generation.getOutput().getText();
              if (!StringUtils.hasText(content)) {
                return;
              }

              assistantResponse.append(content);
              emit(emitter, "token", ChatStreamEvent.token(sessionId, content));
            });
  }

  private void handleCompletion(
      UUID sessionId, StringBuilder assistantResponse, SseEmitter emitter) {
    String content = assistantResponse.toString();
    chatService.registerAssistantMessage(sessionId, content);
    emit(emitter, "complete", ChatStreamEvent.complete(sessionId, content));
    emitter.complete();
  }

  private void handleError(Throwable error, UUID sessionId, SseEmitter emitter) {
    String message = buildErrorMessage(error);
    logError(sessionId, error);
    emit(emitter, "error", ChatStreamEvent.error(sessionId, message));
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

  private Message toAiMessage(ConversationMessage conversationMessage) {
    if (conversationMessage.role() == ChatRole.ASSISTANT) {
      return new AssistantMessage(conversationMessage.content());
    }
    return new UserMessage(conversationMessage.content());
  }
}
