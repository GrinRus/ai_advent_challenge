package com.aiadvent.backend.chat.service;

import com.aiadvent.backend.chat.api.ChatInteractionMode;
import com.aiadvent.backend.chat.api.ChatSyncRequest;
import com.aiadvent.backend.chat.api.ChatSyncResponse;
import com.aiadvent.backend.chat.api.StructuredSyncProvider;
import com.aiadvent.backend.chat.api.StructuredSyncResponse;
import com.aiadvent.backend.chat.api.StructuredSyncUsageStats;
import com.aiadvent.backend.chat.api.UsageCostDetails;
import com.aiadvent.backend.chat.config.ChatProvidersProperties;
import com.aiadvent.backend.chat.domain.ChatStructuredPayload;
import com.aiadvent.backend.chat.memory.ChatSummarizationPreflightManager;
import com.aiadvent.backend.chat.provider.ChatProviderService;
import com.aiadvent.backend.chat.provider.model.ChatProviderSelection;
import com.aiadvent.backend.chat.provider.model.ChatRequestOverrides;
import com.aiadvent.backend.chat.provider.model.UsageCostEstimate;
import com.aiadvent.backend.chat.service.ChatResearchToolBindingService.ResearchContext;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.http.HttpStatus;
import org.springframework.retry.RetryContext;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ResponseStatusException;

@Service
public class SyncChatService extends AbstractSyncService {

  private static final Logger log = LoggerFactory.getLogger(SyncChatService.class);

  private final ChatSummarizationPreflightManager preflightManager;
  private final ChatResearchToolBindingService researchToolBindingService;
  private final BeanOutputConverter<StructuredSyncResponse> structuredOutputConverter;

  public SyncChatService(
      ChatProviderService chatProviderService,
      ChatService chatService,
      ChatSummarizationPreflightManager preflightManager,
      ChatResearchToolBindingService researchToolBindingService,
      BeanOutputConverter<StructuredSyncResponse> structuredOutputConverter) {
    super(chatProviderService, chatService);
    this.preflightManager = preflightManager;
    this.researchToolBindingService = researchToolBindingService;
    this.structuredOutputConverter = structuredOutputConverter;
  }

  public SyncChatResult sync(ChatSyncRequest request) {
    ChatProviderSelection selection = resolveSelection(request.provider(), request.model());
    if (!chatProviderService.supportsSync(selection)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "Model '" + selection.modelId() + "' does not support synchronous responses.");
    }

    ChatInteractionMode mode = ChatInteractionMode.from(request.mode());
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
                      mode,
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
      ChatInteractionMode mode,
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

    String userPrompt = sanitizeMessage(request.message());
    ResearchContext researchContext =
        researchToolBindingService.resolve(mode, userPrompt, request.requestedToolCodes());

    if (log.isDebugEnabled() && researchContext.hasCallbacks()) {
      log.debug(
          "Sync chat using MCP tools {} for session {}",
          researchContext.toolCodes(),
          conversation.sessionId());
    }

    Instant attemptStart = now();
    ChatOptions options = chatProviderService.buildOptions(selection, overrides);

    preflightManager.run(conversation.sessionId(), selection, userPrompt, "sync-chat");

    try {
      var promptSpec = chatProviderService.chatClient(selection.providerId()).prompt();
      if (researchContext.hasSystemPrompt()) {
        promptSpec = promptSpec.system(researchContext.systemPrompt());
      }

      promptSpec =
          promptSpec
              .user(userPrompt)
              .advisors(
                  advisors ->
                      advisors.param(
                          ChatMemory.CONVERSATION_ID, conversation.sessionId().toString()));

      if (researchContext.hasCallbacks()) {
        promptSpec = promptSpec.toolCallbacks(researchContext.callbacks());
      }

      var response = promptSpec.options(options).call().chatResponse();

      String content = extractContent(response);
      if (!StringUtils.hasText(content)) {
        throw new ResponseStatusException(
            HttpStatus.BAD_GATEWAY, "Model returned empty response for sync call");
      }

      ChatResponseMetadata metadata = response.getMetadata();
      Usage usageMetadata = extractUsage(metadata);
      UsageCostEstimate usageCost = estimateUsageCost(selection, usageMetadata, userPrompt, content);
      StructuredSyncUsageStats usageStats = toUsageStats(usageCost);
      UsageCostDetails costDetails = toCostDetails(usageCost);

      StructuredSyncResponse structuredPayload = null;
      ChatStructuredPayload persistedPayload = ChatStructuredPayload.empty();
      if (mode.isResearch()) {
        Optional<JsonNode> node =
            researchToolBindingService.tryParseStructuredPayload(mode, content);
        if (node.isPresent()) {
          try {
            structuredPayload = structuredOutputConverter.convert(node.get().toString());
            persistedPayload = ChatStructuredPayload.from(node.get());
          } catch (Exception parseError) {
            log.debug("Failed to parse structured research payload: {}", parseError.getMessage());
          }
        }
      }

      Instant completedAt = now();
      long latencyMs = Duration.between(attemptStart, completedAt).toMillis();

      List<String> toolCodes =
          CollectionUtils.isEmpty(researchContext.toolCodes())
              ? null
              : List.copyOf(researchContext.toolCodes());

      ChatSyncResponse finalResponse =
          new ChatSyncResponse(
              requestId,
              content,
              new StructuredSyncProvider(provider.getType().name(), selection.modelId()),
              toolCodes,
              structuredPayload,
              usageStats,
              costDetails,
              latencyMs,
              completedAt);

      registerAssistantMessage(
          conversation,
          content,
          selection.providerId(),
          selection.modelId(),
          persistedPayload,
          usageCost);
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
