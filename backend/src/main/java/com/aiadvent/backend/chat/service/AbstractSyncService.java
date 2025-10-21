package com.aiadvent.backend.chat.service;

import com.aiadvent.backend.chat.api.ChatStreamRequestOptions;
import com.aiadvent.backend.chat.api.ChatSyncRequest;
import com.aiadvent.backend.chat.api.StructuredSyncUsageStats;
import com.aiadvent.backend.chat.api.UsageCostDetails;
import com.aiadvent.backend.chat.config.ChatProvidersProperties;
import com.aiadvent.backend.chat.provider.ChatProviderService;
import com.aiadvent.backend.chat.provider.model.ChatProviderSelection;
import com.aiadvent.backend.chat.provider.model.ChatRequestOverrides;
import com.aiadvent.backend.chat.provider.model.UsageCostEstimate;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.slf4j.Logger;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.retry.RetryContext;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.retry.support.RetryTemplateBuilder;
import org.springframework.util.StringUtils;

abstract class AbstractSyncService {

  private static final long MAX_BACKOFF_MS = 10_000L;

  protected final ChatProviderService chatProviderService;
  protected final ChatService chatService;

  private final ConcurrentMap<String, RetryTemplate> retryTemplates = new ConcurrentHashMap<>();

  protected AbstractSyncService(ChatProviderService chatProviderService, ChatService chatService) {
    this.chatProviderService = chatProviderService;
    this.chatService = chatService;
  }

  protected ChatProviderSelection resolveSelection(String provider, String model) {
    return chatProviderService.resolveSelection(provider, model);
  }

  protected ChatProvidersProperties.Provider provider(String providerId) {
    return chatProviderService.provider(providerId);
  }

  protected ChatRequestOverrides resolveOverrides(ChatStreamRequestOptions options) {
    if (options == null) {
      return ChatRequestOverrides.empty();
    }
    return new ChatRequestOverrides(options.temperature(), options.topP(), options.maxTokens());
  }

  protected ConversationContext registerUserMessage(
      ChatSyncRequest request, ChatProviderSelection selection) {
    return chatService.registerUserMessage(
        request.sessionId(), request.message(), selection.providerId(), selection.modelId());
  }

  protected void rollbackUserMessage(ConversationContext context) {
    if (context == null) {
      return;
    }
    chatService.rollbackUserMessage(context.sessionId(), context.messageId(), context.newSession());
  }

  protected RetryTemplate resolveRetryTemplate(
      String providerId, ChatProvidersProperties.Provider providerConfig) {
    return retryTemplates.computeIfAbsent(providerId, key -> buildRetryTemplate(providerConfig));
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
    boolean useExponential = configuredMultiplier != null && configuredMultiplier > 1.0;
    double multiplier = useExponential ? configuredMultiplier : 2.0;
    long maxInterval =
        useExponential
            ? computeMaxInterval(initialInterval, multiplier, attempts)
            : initialInterval;

    Set<Integer> retryableStatuses =
        retryConfig != null && retryConfig.getRetryableStatuses() != null
            ? Set.copyOf(retryConfig.getRetryableStatuses())
            : Set.of(429, 500, 502, 503, 504);

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
                  throwable instanceof AbstractSyncService.SchemaValidationException
                      || SyncExceptions.isRetryableStatus(throwable, retryableStatuses));
    } else {
      builder =
          builder.retryOn(
              throwable ->
                  throwable instanceof AbstractSyncService.SchemaValidationException
                      || SyncExceptions.isRetryableStatus(throwable, null));
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

  protected void logAttemptSuccess(RetryContext context, UUID sessionId, String providerId) {
    int attempt = context != null ? context.getRetryCount() + 1 : 1;
    Logger logger = logger();
    if (attempt > 1) {
      logger.info(
          "Sync attempt {} succeeded for session {} using provider {}",
          attempt,
          sessionId,
          providerId);
    } else if (logger.isDebugEnabled()) {
      logger.debug(
          "Sync attempt {} succeeded for session {} using provider {}",
          attempt,
          sessionId,
          providerId);
    }
  }

  protected void logAttemptFailure(
      RetryContext context, UUID sessionId, String providerId, Throwable throwable) {
    if (throwable == null) {
      return;
    }
    int attempt = context != null ? context.getRetryCount() + 1 : 1;
    String message = throwable.getMessage();
    Logger logger = logger();
    if (logger.isDebugEnabled()) {
      logger.debug(
          "Sync attempt {} failed for session {} using provider {}: {}",
          attempt,
          sessionId,
          providerId,
          message,
          throwable);
    } else {
      logger.info(
          "Sync attempt {} failed for session {} using provider {}: {}",
          attempt,
          sessionId,
          providerId,
          message);
    }
  }

  protected UsageCostEstimate estimateUsageCost(
      ChatProviderSelection selection, Usage usageMetadata, String promptText, String completionText) {
    return chatProviderService.estimateUsageCost(selection, usageMetadata, promptText, completionText);
  }

  protected StructuredSyncUsageStats toUsageStats(UsageCostEstimate usageCost) {
    if (usageCost == null || !usageCost.hasUsage()) {
      return null;
    }
    return new StructuredSyncUsageStats(
        usageCost.promptTokens(), usageCost.completionTokens(), usageCost.totalTokens());
  }

  protected UsageCostDetails toCostDetails(UsageCostEstimate usageCost) {
    if (usageCost == null || !usageCost.hasCost()) {
      return null;
    }
    return new UsageCostDetails(
        usageCost.inputCost(), usageCost.outputCost(), usageCost.totalCost(), usageCost.currency());
  }

  protected Usage extractUsage(ChatResponseMetadata metadata) {
    if (metadata == null) {
      return null;
    }
    return metadata.getUsage();
  }

  protected String extractContent(ChatResponse response) {
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

  protected Logger logger() {
    return SyncExceptions.logger(getClass());
  }

  protected Instant now() {
    return Instant.now();
  }

  protected void registerAssistantMessage(
      ConversationContext context,
      String content,
      String providerId,
      String modelId,
      com.fasterxml.jackson.databind.JsonNode structuredPayload,
      UsageCostEstimate usageCost) {
    chatService.registerAssistantMessage(
        context.sessionId(), content, providerId, modelId, structuredPayload, usageCost);
  }

  protected static final class SchemaValidationException extends RuntimeException {
    protected SchemaValidationException(String message) {
      super(message);
    }

    protected SchemaValidationException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  protected static final class SyncExceptions {

    private SyncExceptions() {}

    private static boolean isRetryableStatus(
        Throwable throwable, Set<Integer> retryableStatuses) {
      if (throwable instanceof SchemaValidationException) {
        return true;
      }
      if (!(throwable instanceof org.springframework.web.reactive.function.client.WebClientResponseException webClientException)) {
        return false;
      }
      if (retryableStatuses == null || retryableStatuses.isEmpty()) {
        return true;
      }
      return retryableStatuses.contains(webClientException.getStatusCode().value());
    }

    private static Logger logger(Class<?> targetClass) {
      return org.slf4j.LoggerFactory.getLogger(targetClass);
    }
  }
}
