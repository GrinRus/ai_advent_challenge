package com.aiadvent.backend.chat.service;

import com.aiadvent.backend.chat.api.ChatInteractionMode;
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
import com.aiadvent.backend.chat.domain.ChatStructuredPayload;
import com.aiadvent.backend.chat.memory.ChatSummarizationPreflightManager;
import com.aiadvent.backend.chat.service.ChatResearchToolBindingService;
import com.aiadvent.backend.chat.provider.ChatProviderService;
import com.aiadvent.backend.chat.provider.model.ChatProviderSelection;
import com.aiadvent.backend.chat.provider.model.ChatRequestOverrides;
import com.aiadvent.backend.chat.provider.model.UsageCostEstimate;
import com.aiadvent.backend.chat.service.ChatResearchToolBindingService.ResearchContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.util.CollectionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatResponse;
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
public class StructuredSyncService extends AbstractSyncService {

  private static final Logger log = LoggerFactory.getLogger(StructuredSyncService.class);

  private static final String JSON_INSTRUCTION_TEMPLATE =
      """
      Ты отвечаешь строгим JSON без какого-либо текста до или после структуры.
      Используй следующую схему и заполняй все обязательные поля:
      %s
      """;

  private final BeanOutputConverter<StructuredSyncResponse> outputConverter;
  private final ObjectMapper objectMapper;
  private final ChatSummarizationPreflightManager preflightManager;
  private final ChatResearchToolBindingService researchToolBindingService;

  public StructuredSyncService(
      ChatProviderService chatProviderService,
      ChatService chatService,
      BeanOutputConverter<StructuredSyncResponse> outputConverter,
      ObjectMapper objectMapper,
      ChatSummarizationPreflightManager preflightManager,
      ChatResearchToolBindingService researchToolBindingService) {
    super(chatProviderService, chatService);
    this.outputConverter = outputConverter;
    this.objectMapper = objectMapper;
    this.preflightManager = preflightManager;
    this.researchToolBindingService = researchToolBindingService;
  }

  public StructuredSyncResult sync(ChatSyncRequest request) {
    ChatProviderSelection selection =
        chatProviderService.resolveSelection(request.provider(), request.model());
    if (!chatProviderService.supportsStructured(selection)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "Model '" + selection.modelId() + "' does not support structured responses.");
    }
    ChatInteractionMode mode = ChatInteractionMode.from(request.mode());
    ChatProvidersProperties.Provider provider = provider(selection.providerId());
    ChatRequestOverrides overrides = resolveOverrides(request.options());
    ConversationContext context = registerUserMessage(request, selection);
    UUID requestId = UUID.randomUUID();

    RetryTemplate retryTemplate = resolveRetryTemplate(selection.providerId(), provider);

    try {
      StructuredSyncResponse response =
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
      return new StructuredSyncResult(context, response);
    } catch (RuntimeException ex) {
      rollbackUserMessage(context);
      throw ex;
    }
  }

  private StructuredSyncResponse executeAttempt(
      ChatSyncRequest request,
      ChatInteractionMode mode,
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

    Instant attemptStart = now();
    ChatOptions options =
        chatProviderService.buildStructuredOptions(selection, overrides, outputConverter);

    String sanitizedPrompt = sanitizeUserPrompt(request.message());
    ResearchContext researchContext = researchToolBindingService.resolve(mode, sanitizedPrompt);

    String systemInstruction =
        JSON_INSTRUCTION_TEMPLATE.formatted(outputConverter.getFormat().trim());
    if (mode.isResearch() && researchContext.hasSystemPrompt()) {
      systemInstruction = researchContext.systemPrompt() + "\n\n" + systemInstruction;
    }

    String userPrompt =
        enrichUserPrompt(sanitizedPrompt, provider.getType(), mode, researchContext);

    preflightManager.run(conversation.sessionId(), selection, userPrompt, "structured-sync");

    try {
      var prompt =
          chatProviderService
              .chatClient(selection.providerId())
              .prompt()
              .system(systemInstruction)
              .user(userPrompt)
              .advisors(
                  advisors ->
                      advisors.param(
                          ChatMemory.CONVERSATION_ID, conversation.sessionId().toString()));

      if (researchContext.hasCallbacks()) {
        prompt = prompt.toolCallbacks(researchContext.callbacks());
      }

      ChatResponse response = prompt.options(options).call().chatResponse();

      String content = extractContent(response);
      if (!StringUtils.hasText(content)) {
        throw new SchemaValidationException("Model returned empty response for structured sync");
      }

      StructuredSyncResponse payload = convert(content);
      Usage usageMetadata = extractUsage(response.getMetadata());
      UsageCostEstimate usageCost = estimateUsageCost(selection, usageMetadata, userPrompt, content);
      StructuredSyncUsageStats usageStats = toUsageStats(usageCost);
      UsageCostDetails costDetails = toCostDetails(usageCost);
      long latencyMs = Duration.between(attemptStart, Instant.now()).toMillis();

      List<String> toolCodes =
          researchContext.hasCallbacks() && !CollectionUtils.isEmpty(researchContext.toolCodes())
              ? List.copyOf(researchContext.toolCodes())
              : null;

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
              toolCodes,
              usageStats,
              costDetails,
              latencyMs,
              now());

      JsonNode structuredPayload = serializePayload(finalResponse);
      registerAssistantMessage(
          conversation,
          content,
          selection.providerId(),
          selection.modelId(),
          ChatStructuredPayload.from(structuredPayload),
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
      logger()
          .warn(
              "Structured sync failed after {} attempt(s) for session {} using provider {}: {}",
              attempts,
              sessionId,
              providerId,
              lastThrowable.getMessage());
    } else {
      logger()
          .warn(
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

  private String enrichUserPrompt(
      String sanitizedMessage,
      ChatProviderType providerType,
      ChatInteractionMode mode,
      ResearchContext researchContext) {
    if (!StringUtils.hasText(sanitizedMessage)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Message must not be empty");
    }
    StringBuilder builder = new StringBuilder(sanitizedMessage);
    if (mode.isResearch() && researchContext.hasStructuredAdvice()) {
      builder.append("\n\n").append(researchContext.structuredAdvice().trim());
    }
    String directive = "Ответь исключительно JSON-объектом без Markdown и комментариев.";
    builder.append("\n\n").append(directive);
    if (providerType != ChatProviderType.OPENAI) {
      builder.append("\n").append(outputConverter.getFormat());
    }
    return builder.toString();
  }

  private String sanitizeUserPrompt(String message) {
    String trimmed = message != null ? message.trim() : "";
    if (!StringUtils.hasText(trimmed)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Message must not be empty");
    }
    return trimmed;
  }

  private StructuredSyncResponse convert(String content) {
    try {
      return outputConverter.convert(content);
    } catch (Exception ex) {
      throw new SchemaValidationException("Model response does not conform to JSON schema", ex);
    }
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

  public record StructuredSyncResult(ConversationContext context, StructuredSyncResponse response) {}

  @Override
  protected Logger logger() {
    return log;
  }

}
