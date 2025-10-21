package com.aiadvent.backend.chat.service;

import com.aiadvent.backend.chat.api.ChatSyncRequest;
import com.aiadvent.backend.chat.api.ChatSyncResponse;
import com.aiadvent.backend.chat.api.StructuredSyncProvider;
import com.aiadvent.backend.chat.api.StructuredSyncUsageStats;
import com.aiadvent.backend.chat.api.UsageCostDetails;
import com.aiadvent.backend.chat.config.ChatProvidersProperties;
import com.aiadvent.backend.chat.provider.ChatProviderService;
import com.aiadvent.backend.chat.provider.model.ChatProviderSelection;
import com.aiadvent.backend.chat.provider.model.ChatRequestOverrides;
import com.aiadvent.backend.chat.provider.model.UsageCostEstimate;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.http.HttpStatus;
import org.springframework.retry.RetryContext;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ResponseStatusException;

@Service
public class SyncChatService extends AbstractSyncService {

  private static final Logger log = LoggerFactory.getLogger(SyncChatService.class);

  public SyncChatService(ChatProviderService chatProviderService, ChatService chatService) {
    super(chatProviderService, chatService);
  }

  public SyncChatResult sync(ChatSyncRequest request) {
    ChatProviderSelection selection = resolveSelection(request.provider(), request.model());
    if (!chatProviderService.supportsSync(selection)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "Model '" + selection.modelId() + "' does not support synchronous responses.");
    }
    ChatProvidersProperties.Provider provider = provider(selection.providerId());
    ChatRequestOverrides overrides = resolveOverrides(request.options());
    ConversationContext context = registerUserMessage(request, selection);
    UUID requestId = UUID.randomUUID();

    RetryTemplate retryTemplate = resolveRetryTemplate(selection.providerId(), provider);

    try {
      ChatSyncResponse response =
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
              this::handleFailure);
      return new SyncChatResult(context, response);
    } catch (RuntimeException ex) {
      rollbackUserMessage(context);
      throw ex;
    }
  }

  private ChatSyncResponse executeAttempt(
      ChatSyncRequest request,
      ConversationContext conversation,
      ChatProviderSelection selection,
      ChatProvidersProperties.Provider provider,
      ChatRequestOverrides overrides,
      UUID requestId,
      RetryContext retryContext) {
    if (retryContext != null) {
      retryContext.setAttribute("sessionId", conversation.sessionId());
      retryContext.setAttribute("providerId", selection.providerId());
    }

    Instant attemptStart = now();
    ChatOptions options = chatProviderService.buildOptions(selection, overrides);
    String userPrompt = sanitizeMessage(request.message());

    try {
      var response =
          chatProviderService
              .chatClient(selection.providerId())
              .prompt()
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
        throw new ResponseStatusException(
            HttpStatus.BAD_GATEWAY, "Model returned empty response for sync call");
      }

      ChatResponseMetadata metadata = response.getMetadata();
      Usage usageMetadata = extractUsage(metadata);
      UsageCostEstimate usageCost = estimateUsageCost(selection, usageMetadata);
      StructuredSyncUsageStats usageStats = toUsageStats(usageCost);
      UsageCostDetails costDetails = toCostDetails(usageCost);

      Instant completedAt = now();
      long latencyMs = Duration.between(attemptStart, completedAt).toMillis();

      ChatSyncResponse finalResponse =
          new ChatSyncResponse(
              requestId,
              content,
              new StructuredSyncProvider(provider.getType().name(), selection.modelId()),
              usageStats,
              costDetails,
              latencyMs,
              completedAt);

      registerAssistantMessage(conversation, content, selection.providerId(), selection.modelId(), null, usageCost);
      logAttemptSuccess(retryContext, conversation.sessionId(), selection.providerId());
      return finalResponse;
    } catch (RuntimeException ex) {
      logAttemptFailure(retryContext, conversation.sessionId(), selection.providerId(), ex);
      throw ex;
    }
  }

  private ChatSyncResponse handleFailure(RetryContext retryContext) {
    Throwable lastThrowable = retryContext != null ? retryContext.getLastThrowable() : null;
    UUID sessionId = retryContext != null ? (UUID) retryContext.getAttribute("sessionId") : null;
    String providerId =
        retryContext != null ? (String) retryContext.getAttribute("providerId") : null;
    int attempts = retryContext != null ? retryContext.getRetryCount() + 1 : 0;

    if (lastThrowable != null) {
      logger()
          .warn(
              "Sync call failed after {} attempt(s) for session {} using provider {}: {}",
              attempts,
              sessionId,
              providerId,
              lastThrowable.getMessage());
    } else {
      logger()
          .warn(
              "Sync call failed after {} attempt(s) for session {} using provider {}",
              attempts,
              sessionId,
              providerId);
    }

    return mapFailure(lastThrowable);
  }

  private ChatSyncResponse mapFailure(Throwable lastThrowable) {
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
          responseStatus, "Failed to obtain sync response", webClientError);
    }
    throw new ResponseStatusException(
        HttpStatus.BAD_GATEWAY, "Failed to obtain sync response", lastThrowable);
  }

  private String sanitizeMessage(String message) {
    String trimmed = message != null ? message.trim() : "";
    if (!StringUtils.hasText(trimmed)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Message must not be empty");
    }
    return trimmed;
  }

  @Override
  protected Logger logger() {
    return log;
  }

  public record SyncChatResult(ConversationContext context, ChatSyncResponse response) {}
}
