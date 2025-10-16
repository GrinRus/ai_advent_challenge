package com.aiadvent.backend.chat.service;

import com.aiadvent.backend.chat.api.ChatStreamRequestOptions;
import com.aiadvent.backend.chat.api.ChatSyncRequest;
import com.aiadvent.backend.chat.api.StructuredSyncAnswer;
import com.aiadvent.backend.chat.api.StructuredSyncProvider;
import com.aiadvent.backend.chat.api.StructuredSyncResponse;
import com.aiadvent.backend.chat.api.StructuredSyncStatus;
import com.aiadvent.backend.chat.api.StructuredSyncUsageStats;
import com.aiadvent.backend.chat.config.ChatProviderType;
import com.aiadvent.backend.chat.config.ChatProvidersProperties;
import com.aiadvent.backend.chat.provider.ChatProviderService;
import com.aiadvent.backend.chat.provider.model.ChatProviderSelection;
import com.aiadvent.backend.chat.provider.model.ChatRequestOverrides;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.http.HttpStatus;
import org.springframework.retry.RetryContext;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ResponseStatusException;

@Service
public class StructuredSyncService {

  private static final Logger log = LoggerFactory.getLogger(StructuredSyncService.class);

  private static final String JSON_INSTRUCTION_TEMPLATE =
      """Ты отвечаешь строгим JSON без какого-либо текста до или после структуры.
      Используй следующую схему и заполняй все обязательные поля:
      %s
      """;

  private final ChatProviderService chatProviderService;
  private final ChatService chatService;
  private final BeanOutputConverter<StructuredSyncResponse> outputConverter;
  private final RetryTemplate retryTemplate;

  public StructuredSyncService(
      ChatProviderService chatProviderService,
      ChatService chatService,
      BeanOutputConverter<StructuredSyncResponse> outputConverter) {
    this.chatProviderService = chatProviderService;
    this.chatService = chatService;
    this.outputConverter = outputConverter;
    this.retryTemplate =
        RetryTemplate.builder()
            .maxAttempts(3)
            .exponentialBackoff(250, 2.0, 1000)
            .retryOn(WebClientResponseException.class)
            .retryOn(SchemaValidationException.class)
            .build();
  }

  public StructuredSyncResult sync(ChatSyncRequest request) {
    ChatProviderSelection selection =
        chatProviderService.resolveSelection(request.provider(), request.model());
    ChatProvidersProperties.Provider provider = chatProviderService.provider(selection.providerId());
    ChatRequestOverrides overrides = resolveOverrides(request.options());
    ConversationContext context =
        chatService.registerUserMessage(
            request.sessionId(),
            request.message(),
            selection.providerId(),
            selection.modelId());
    UUID requestId = UUID.randomUUID();

    StructuredSyncResponse response =
        retryTemplate.execute(
        retryContext ->
            executeAttempt(
                request,
                context,
                selection,
                provider,
                overrides,
                requestId,
                retryContext),
        recovery -> handleFailure(recovery.getLastThrowable()));
    return new StructuredSyncResult(context, response);
  }

  private StructuredSyncResponse executeAttempt(
      ChatSyncRequest request,
      ConversationContext conversation,
      ChatProviderSelection selection,
      ChatProvidersProperties.Provider provider,
      ChatRequestOverrides overrides,
      UUID requestId,
      RetryContext retryContext) {
    Instant attemptStart = Instant.now();
    ChatOptions options =
        chatProviderService.buildStructuredOptions(selection, overrides, outputConverter);

    String systemInstruction =
        JSON_INSTRUCTION_TEMPLATE.formatted(outputConverter.getFormat().trim());
    String userPrompt = enrichUserPrompt(request.message(), provider.getType());

    ChatResponse response =
        chatProviderService
            .chatClient(selection.providerId())
            .prompt()
            .system(systemInstruction)
            .user(userPrompt)
            .advisors(
                advisors ->
                    advisors.param(ChatMemory.CONVERSATION_ID, conversation.sessionId().toString()))
            .options(options)
            .call();

    String content = extractContent(response);
    if (!StringUtils.hasText(content)) {
      throw new SchemaValidationException("Model returned empty response for structured sync");
    }

    StructuredSyncResponse payload = convert(content);
    StructuredSyncUsageStats usage = resolveUsage(response.getMetadata());
    long latencyMs = Duration.between(attemptStart, Instant.now()).toMillis();

    StructuredSyncStatus status =
        payload.status() != null ? payload.status() : StructuredSyncStatus.SUCCESS;
    StructuredSyncAnswer answer = payload.answer();
    if (answer == null) {
      throw new SchemaValidationException("Structured answer is missing in the model response");
    }

    StructuredSyncResponse finalResponse =
        new StructuredSyncResponse(
            payload.requestId() != null ? payload.requestId() : requestId,
            status,
            new StructuredSyncProvider(provider.getType().name(), selection.modelId()),
            answer,
            usage,
            latencyMs,
            Instant.now());

    chatService.registerAssistantMessage(
        conversation.sessionId(), content, selection.providerId(), selection.modelId());

    if (log.isDebugEnabled()) {
      log.debug(
          "Structured sync attempt {} succeeded for session {} using provider {}",
          retryContext.getRetryCount() + 1,
          conversation.sessionId(),
          selection.providerId());
    }

    return finalResponse;
  }

  private StructuredSyncResponse handleFailure(Throwable lastThrowable) {
    if (lastThrowable instanceof SchemaValidationException schemaError) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_ENTITY, schemaError.getMessage(), schemaError);
    }
    if (lastThrowable instanceof ResponseStatusException responseStatusException) {
      throw responseStatusException;
    }
    if (lastThrowable instanceof WebClientResponseException webClientError) {
      HttpStatus status = HttpStatus.resolve(webClientError.getStatusCode().value());
      HttpStatus responseStatus =
          status != null && status == HttpStatus.TOO_MANY_REQUESTS
              ? HttpStatus.TOO_MANY_REQUESTS
              : HttpStatus.BAD_GATEWAY;
      throw new ResponseStatusException(
          responseStatus, "Failed to obtain structured sync response", webClientError);
    }
    throw new ResponseStatusException(
        HttpStatus.BAD_GATEWAY, "Failed to obtain structured sync response", lastThrowable);
  }

  private String enrichUserPrompt(String message, ChatProviderType providerType) {
    String trimmed = message != null ? message.trim() : "";
    if (!StringUtils.hasText(trimmed)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Message must not be empty");
    }
    String directive = "Ответь исключительно JSON-объектом без Markdown и комментариев.";
    if (providerType == ChatProviderType.OPENAI) {
      return trimmed + "\n\n" + directive;
    }
    return trimmed + "\n\n" + directive + "\n" + outputConverter.getFormat();
  }

  private StructuredSyncResponse convert(String content) {
    try {
      return outputConverter.convert(content);
    } catch (Exception ex) {
      throw new SchemaValidationException("Model response does not conform to JSON schema", ex);
    }
  }

  private StructuredSyncUsageStats resolveUsage(ChatResponseMetadata metadata) {
    if (metadata == null || metadata.getUsage() == null) {
      return null;
    }
    Usage usage = metadata.getUsage();
    return new StructuredSyncUsageStats(
        usage.getPromptTokens(), usage.getCompletionTokens(), usage.getTotalTokens());
  }

  private String extractContent(ChatResponse response) {
    if (response == null) {
      return null;
    }
    List<Generation> generations = response.getResults();
    if (generations == null || generations.isEmpty()) {
      return null;
    }
    StringBuilder builder = new StringBuilder();
    for (Generation generation : generations) {
      if (generation == null || generation.getOutput() == null) {
        continue;
      }
      String text = generation.getOutput().getText();
      if (StringUtils.hasText(text)) {
        builder.append(text);
      }
    }
    return builder.toString();
  }

  private ChatRequestOverrides resolveOverrides(ChatStreamRequestOptions options) {
    if (options == null) {
      return ChatRequestOverrides.empty();
    }
    return new ChatRequestOverrides(options.temperature(), options.topP(), options.maxTokens());
  }

  private static final class SchemaValidationException extends RuntimeException {
    private SchemaValidationException(String message) {
      super(message);
    }

    private SchemaValidationException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  public record StructuredSyncResult(ConversationContext context, StructuredSyncResponse response) {}
}
