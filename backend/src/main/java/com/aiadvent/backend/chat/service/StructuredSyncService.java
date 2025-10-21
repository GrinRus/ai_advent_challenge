package com.aiadvent.backend.chat.service;

import com.aiadvent.backend.chat.api.ChatStreamRequestOptions;
import com.aiadvent.backend.chat.api.ChatSyncRequest;
import com.aiadvent.backend.chat.api.StructuredSyncAnswer;
import com.aiadvent.backend.chat.api.StructuredSyncProvider;
import com.aiadvent.backend.chat.api.StructuredSyncResponse;
import com.aiadvent.backend.chat.api.StructuredSyncStatus;
import com.aiadvent.backend.chat.api.StructuredSyncUsageStats;
import com.aiadvent.backend.chat.api.UsageCostDetails;
import com.aiadvent.backend.chat.config.ChatProviderType;
import com.aiadvent.backend.chat.config.ChatProvidersProperties;
import com.aiadvent.backend.chat.provider.ChatProviderService;
import com.aiadvent.backend.chat.provider.model.ChatProviderSelection;
import com.aiadvent.backend.chat.provider.model.ChatRequestOverrides;
import com.aiadvent.backend.chat.provider.model.UsageCostEstimate;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
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
import org.springframework.retry.support.RetryTemplateBuilder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ResponseStatusException;

@Service
public class StructuredSyncService {

  private static final Logger log = LoggerFactory.getLogger(StructuredSyncService.class);
  private static final long MAX_BACKOFF_MS = 10_000L;

  private static final String JSON_INSTRUCTION_TEMPLATE =
      """
      Ты отвечаешь строгим JSON без какого-либо текста до или после структуры.
      Используй следующую схему и заполняй все обязательные поля:
      %s
      """;

  private final ChatProviderService chatProviderService;
  private final ChatService chatService;
  private final BeanOutputConverter<StructuredSyncResponse> outputConverter;
  private final ObjectMapper objectMapper;
  private final ConcurrentMap<String, RetryTemplate> retryTemplates = new ConcurrentHashMap<>();

  public StructuredSyncService(
      ChatProviderService chatProviderService,
      ChatService chatService,
      BeanOutputConverter<StructuredSyncResponse> outputConverter,
      ObjectMapper objectMapper) {
    this.chatProviderService = chatProviderService;
    this.chatService = chatService;
    this.outputConverter = outputConverter;
    this.objectMapper = objectMapper;
  }

  public StructuredSyncResult sync(ChatSyncRequest request) {
    ChatProviderSelection selection =
        chatProviderService.resolveSelection(request.provider(), request.model());
    if (!chatProviderService.supportsStructured(selection)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "Model '" + selection.modelId() + "' does not support structured responses.");
    }
    ChatProvidersProperties.Provider provider = chatProviderService.provider(selection.providerId());
    ChatRequestOverrides overrides = resolveOverrides(request.options());
    ConversationContext context =
        chatService.registerUserMessage(
            request.sessionId(),
            request.message(),
            selection.providerId(),
            selection.modelId());
    UUID requestId = UUID.randomUUID();

    RetryTemplate retryTemplate = resolveRetryTemplate(selection.providerId(), provider);

    try {
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
              recovery -> handleFailure(recovery));
      return new StructuredSyncResult(context, response);
    } catch (RuntimeException ex) {
      chatService.rollbackUserMessage(
          context.sessionId(), context.messageId(), context.newSession());
      throw ex;
    }
  }

  private StructuredSyncResponse executeAttempt(
      ChatSyncRequest request,
      ConversationContext conversation,
      ChatProviderSelection selection,
      ChatProvidersProperties.Provider provider,
      ChatRequestOverrides overrides,
      UUID requestId,
      RetryContext retryContext)
      throws ResponseStatusException {
    if (retryContext != null) {
      retryContext.setAttribute("sessionId", conversation.sessionId());
      retryContext.setAttribute("providerId", selection.providerId());
    }

    Instant attemptStart = Instant.now();
    ChatOptions options =
        chatProviderService.buildStructuredOptions(selection, overrides, outputConverter);

    String systemInstruction =
        JSON_INSTRUCTION_TEMPLATE.formatted(outputConverter.getFormat().trim());
    String userPrompt = enrichUserPrompt(request.message(), provider.getType());

    try {
      ChatResponse response =
          chatProviderService
              .chatClient(selection.providerId())
              .prompt()
              .system(systemInstruction)
              .user(userPrompt)
              .advisors(
                  advisors ->
                      advisors.param(
                          ChatMemory.CONVERSATION_ID, conversation.sessionId().toString()))
              .options(options)
              .call()
              .chatResponse();

      String content = extractContent(response);
      if (!StringUtils.hasText(content)) {
        throw new SchemaValidationException("Model returned empty response for structured sync");
      }

      StructuredSyncResponse payload = convert(content);
      Usage usageMetadata = extractUsage(response.getMetadata());
      UsageCostEstimate usageCost = chatProviderService.estimateUsageCost(selection, usageMetadata);
      StructuredSyncUsageStats usageStats = toUsageStats(usageCost);
      UsageCostDetails costDetails = toCostDetails(usageCost);
      long latencyMs = Duration.between(attemptStart, Instant.now()).toMillis();

      StructuredSyncStatus status =
          payload.status() != null ? payload.status() : StructuredSyncStatus.SUCCESS;
      StructuredSyncAnswer answer = payload.answer();
      if (answer == null) {
        throw new SchemaValidationException(
            "Structured answer is missing in the model response");
      }

      StructuredSyncResponse finalResponse =
          new StructuredSyncResponse(
              payload.requestId() != null ? payload.requestId() : requestId,
              status,
              new StructuredSyncProvider(provider.getType().name(), selection.modelId()),
              answer,
              usageStats,
              costDetails,
              latencyMs,
              Instant.now());

      JsonNode structuredPayload = serializePayload(finalResponse);
      chatService.registerAssistantMessage(
          conversation.sessionId(),
          content,
          selection.providerId(),
          selection.modelId(),
          structuredPayload,
          usageCost);

      logAttemptSuccess(retryContext, conversation.sessionId(), selection.providerId());
      return finalResponse;
    } catch (RuntimeException ex) {
      logAttemptFailure(retryContext, conversation.sessionId(), selection.providerId(), ex);
      throw ex;
    }
  }

  private StructuredSyncResponse handleFailure(RetryContext retryContext) {
    Throwable lastThrowable = retryContext != null ? retryContext.getLastThrowable() : null;
    UUID sessionId = retryContext != null ? (UUID) retryContext.getAttribute("sessionId") : null;
    String providerId =
        retryContext != null ? (String) retryContext.getAttribute("providerId") : null;
    int attempts = retryContext != null ? retryContext.getRetryCount() + 1 : 0;

    if (lastThrowable != null) {
      log.warn(
          "Structured sync failed after {} attempt(s) for session {} using provider {}: {}",
          attempts,
          sessionId,
          providerId,
          lastThrowable.getMessage());
    } else {
      log.warn(
          "Structured sync failed after {} attempt(s) for session {} using provider {}",
          attempts,
          sessionId,
          providerId);
    }

    return mapFailure(lastThrowable);
  }

  private StructuredSyncResponse mapFailure(Throwable lastThrowable) {
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

  private Usage extractUsage(ChatResponseMetadata metadata) {
    if (metadata == null) {
      return null;
    }
    return metadata.getUsage();
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

  private RetryTemplate resolveRetryTemplate(
      String providerId, ChatProvidersProperties.Provider providerConfig) {
    return retryTemplates.computeIfAbsent(
        providerId, key -> buildRetryTemplate(providerConfig));
  }

  private RetryTemplate buildRetryTemplate(ChatProvidersProperties.Provider providerConfig) {
    ChatProvidersProperties.Retry retryConfig =
        providerConfig != null ? providerConfig.getRetry() : null;

    int attempts = retryConfig != null ? Math.max(1, retryConfig.getAttempts()) : 3;
    long initialInterval =
        retryConfig != null && retryConfig.getInitialDelay() != null
            ? Math.max(1L, retryConfig.getInitialDelay().toMillis())
            : 250L;
    Double configuredMultiplier = retryConfig != null ? retryConfig.getMultiplier() : null;
    boolean useExponential =
        configuredMultiplier != null && configuredMultiplier > 1.0;
    double multiplier = useExponential ? configuredMultiplier : 2.0;
    long maxInterval = useExponential ? computeMaxInterval(initialInterval, multiplier, attempts) : initialInterval;

    Set<Integer> retryableStatuses =
        retryConfig != null && retryConfig.getRetryableStatuses() != null
            ? new HashSet<>(retryConfig.getRetryableStatuses())
            : new HashSet<>(List.of(429, 500, 502, 503, 504));

    RetryTemplateBuilder builder = RetryTemplate.builder().maxAttempts(attempts);

    if (useExponential) {
      builder = builder.exponentialBackoff(initialInterval, multiplier, maxInterval);
    } else {
      builder = builder.fixedBackoff(initialInterval);
    }

    if (!retryableStatuses.isEmpty()) {
      builder =
          builder.retryOn(
              throwable ->
                  throwable instanceof SchemaValidationException
                      || (throwable instanceof WebClientResponseException webClientException
                          && retryableStatuses.contains(webClientException.getStatusCode().value())));
    } else {
      builder = builder.retryOn(
          throwable ->
              throwable instanceof SchemaValidationException
                  || throwable instanceof WebClientResponseException);
    }

    return builder.build();
  }

  private long computeMaxInterval(long initialInterval, double multiplier, int attempts) {
    double current = initialInterval;
    double max = initialInterval;
    for (int i = 1; i < attempts; i++) {
      current = Math.min(current * multiplier, MAX_BACKOFF_MS);
      if (current > max) {
        max = current;
      }
    }
    long result = (long) Math.max(initialInterval, Math.min(max, MAX_BACKOFF_MS));
    return result > 0 ? result : initialInterval;
  }

  private JsonNode serializePayload(StructuredSyncResponse response) {
    if (response == null) {
      return null;
    }
    try {
      return objectMapper.valueToTree(response);
    } catch (IllegalArgumentException ex) {
      log.warn(
          "Failed to serialize structured payload for request {}: {}",
          response.requestId(),
          ex.getMessage());
      return null;
    }
  }

  private void logAttemptSuccess(RetryContext context, UUID sessionId, String providerId) {
    int attempt = context != null ? context.getRetryCount() + 1 : 1;
    if (attempt > 1) {
      log.info(
          "Structured sync attempt {} succeeded for session {} using provider {}",
          attempt,
          sessionId,
          providerId);
    } else if (log.isDebugEnabled()) {
      log.debug(
          "Structured sync attempt {} succeeded for session {} using provider {}",
          attempt,
          sessionId,
          providerId);
    }
  }

  private void logAttemptFailure(
      RetryContext context, UUID sessionId, String providerId, Throwable throwable) {
    if (throwable == null) {
      return;
    }
    int attempt = context != null ? context.getRetryCount() + 1 : 1;
    String message = throwable.getMessage();
    if (log.isDebugEnabled()) {
      log.debug(
          "Structured sync attempt {} failed for session {} using provider {}: {}",
          attempt,
          sessionId,
          providerId,
          message,
          throwable);
    } else {
      log.info(
          "Structured sync attempt {} failed for session {} using provider {}: {}",
          attempt,
          sessionId,
          providerId,
          message);
    }
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
