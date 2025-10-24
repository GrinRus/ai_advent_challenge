package com.aiadvent.backend.flow.service;

import com.aiadvent.backend.chat.config.ChatProvidersProperties;
import com.aiadvent.backend.chat.provider.ChatProviderService;
import com.aiadvent.backend.chat.provider.model.ChatProviderSelection;
import com.aiadvent.backend.chat.provider.model.ChatRequestOverrides;
import com.aiadvent.backend.chat.provider.model.UsageCostEstimate;
import com.aiadvent.backend.flow.domain.AgentVersion;
import com.aiadvent.backend.flow.domain.FlowSession;
import com.aiadvent.backend.flow.memory.FlowMemoryService;
import com.aiadvent.backend.flow.persistence.FlowSessionRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.retry.RetryContext;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.retry.support.RetryTemplateBuilder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClientResponseException;

@Service
public class AgentInvocationService {

  private static final Logger log = LoggerFactory.getLogger(AgentInvocationService.class);
  private static final long MAX_BACKOFF_MS = 10_000L;

  private final ChatProviderService chatProviderService;
  private final FlowSessionRepository flowSessionRepository;
  private final FlowMemoryService flowMemoryService;
  private final ObjectMapper objectMapper;
  private final ConcurrentMap<String, RetryTemplate> retryTemplates = new ConcurrentHashMap<>();

  public AgentInvocationService(
      ChatProviderService chatProviderService,
      FlowSessionRepository flowSessionRepository,
      FlowMemoryService flowMemoryService,
      ObjectMapper objectMapper) {
    this.chatProviderService = chatProviderService;
    this.flowSessionRepository = flowSessionRepository;
    this.flowMemoryService = flowMemoryService;
    this.objectMapper = objectMapper;
  }

  public AgentInvocationResult invoke(AgentInvocationRequest request) {
    FlowSession flowSession =
        flowSessionRepository
            .findById(request.flowSessionId())
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "Flow session not found: " + request.flowSessionId()));

    AgentVersion agentVersion = request.agentVersion();
    ChatProviderSelection selection =
      chatProviderService.resolveSelection(agentVersion.getProviderId(), agentVersion.getModelId());
    ChatProvidersProperties.Provider providerConfig = chatProviderService.provider(selection.providerId());

    RetryTemplate retryTemplate = resolveRetryTemplate(selection.providerId(), providerConfig);

    return retryTemplate.execute(
        context -> executeAttempt(request, flowSession, agentVersion, selection, context),
        context -> {
          Throwable last = context != null ? context.getLastThrowable() : null;
          if (last instanceof RuntimeException runtime) {
            throw runtime;
          }
          throw new IllegalStateException("Agent invocation failed", last);
        });
  }

  private AgentInvocationResult executeAttempt(
      AgentInvocationRequest request,
      FlowSession flowSession,
      AgentVersion agentVersion,
      ChatProviderSelection selection,
      RetryContext retryContext) {

    if (retryContext != null) {
      retryContext.setAttribute("flowSessionId", flowSession.getId());
    }

    Instant started = Instant.now();
    ChatRequestOverrides effectiveOverrides =
        computeOverrides(agentVersion, request.stepOverrides(), request.sessionOverrides());
    String userMessage =
        buildUserMessage(request.userPrompt(), request.launchParameters(), request.inputContext());
    List<String> memoryMessages = buildMemorySnapshots(flowSession, request.memoryReads());
    Map<String, Object> advisorParams = new java.util.HashMap<>();
    if (request.stepId() != null) {
      advisorParams.put("flowStepId", request.stepId().toString());
    }

    try {
      ChatResponse chatResponse =
          chatProviderService.chatSyncWithOverrides(
              selection,
              agentVersion.getSystemPrompt(),
              memoryMessages,
              advisorParams,
              userMessage,
              effectiveOverrides);

      String content = extractContent(chatResponse);
      if (!StringUtils.hasText(content)) {
        throw new IllegalStateException("Agent returned empty response");
      }

      Usage usage = extractUsage(chatResponse.getMetadata());
      UsageCostEstimate usageCost =
          chatProviderService.estimateUsageCost(
              selection, usage, request.userPrompt(), content);

      List<com.aiadvent.backend.flow.domain.FlowMemoryVersion> memoryUpdates =
          applyMemoryWrites(flowSession.getId(), request.memoryWrites(), request.stepId());

      logAttemptSuccess(retryContext, flowSession.getId(), selection.providerId());

      long latencyMs = Duration.between(started, Instant.now()).toMillis();
      if (log.isDebugEnabled()) {
        log.debug(
            "Agent invocation for session {} using provider {} completed in {} ms",
            flowSession.getId(),
            selection.providerId(),
            latencyMs);
      }

      return new AgentInvocationResult(
          content,
          usageCost,
          memoryUpdates,
          selection,
          effectiveOverrides,
          agentVersion.getSystemPrompt(),
          memoryMessages,
          userMessage);

    } catch (RuntimeException ex) {
      logAttemptFailure(retryContext, flowSession.getId(), selection.providerId(), ex);
      throw ex;
    }
  }

  private ChatRequestOverrides computeOverrides(
      AgentVersion agentVersion,
      ChatRequestOverrides stepOverrides,
      ChatRequestOverrides sessionOverrides) {
    Double temperature = null;
    Double topP = null;
    Integer maxTokens = agentVersion.getMaxTokens();

    JsonNode defaultOptions = agentVersion.getDefaultOptions();
    if (defaultOptions != null) {
      if (defaultOptions.hasNonNull("temperature")) {
        temperature = defaultOptions.get("temperature").asDouble();
      }
      if (defaultOptions.hasNonNull("topP")) {
        topP = defaultOptions.get("topP").asDouble();
      }
      if (defaultOptions.hasNonNull("maxTokens")) {
        maxTokens = defaultOptions.get("maxTokens").asInt();
      }
    }

    temperature = applyOverride(temperature, stepOverrides != null ? stepOverrides.temperature() : null);
    topP = applyOverride(topP, stepOverrides != null ? stepOverrides.topP() : null);
    maxTokens = applyOverride(maxTokens, stepOverrides != null ? stepOverrides.maxTokens() : null);

    temperature = applyOverride(temperature, sessionOverrides != null ? sessionOverrides.temperature() : null);
    topP = applyOverride(topP, sessionOverrides != null ? sessionOverrides.topP() : null);
    maxTokens = applyOverride(maxTokens, sessionOverrides != null ? sessionOverrides.maxTokens() : null);

    if (temperature == null && topP == null && maxTokens == null) {
      return ChatRequestOverrides.empty();
    }
    return new ChatRequestOverrides(temperature, topP, maxTokens);
  }

  private Double applyOverride(Double current, Double overrideValue) {
    return overrideValue != null ? overrideValue : current;
  }

  private Integer applyOverride(Integer current, Integer overrideValue) {
    return overrideValue != null ? overrideValue : current;
  }

  private String buildUserMessage(
      String userPrompt, JsonNode launchParameters, JsonNode inputContext) {
    String sanitizedPrompt = userPrompt != null ? userPrompt.trim() : "";
    if (!StringUtils.hasText(sanitizedPrompt)) {
      throw new IllegalArgumentException("userPrompt must not be blank");
    }
    try {
      StringBuilder builder = new StringBuilder(sanitizedPrompt);
      if (hasContent(launchParameters)) {
        builder
            .append("\n\nLaunch Parameters:\n")
            .append(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(launchParameters));
      }
      JsonNode sanitizedContext = sanitizeInputContext(inputContext);
      if (hasContent(sanitizedContext)) {
        builder
            .append("\n\nContext:\n")
            .append(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(sanitizedContext));
      }
      return builder.toString();
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Failed to serialize input context", exception);
    }
  }

  private JsonNode sanitizeInputContext(JsonNode inputContext) {
    if (inputContext == null || inputContext.isNull()) {
      return null;
    }
    if (!inputContext.isObject()) {
      return inputContext;
    }

    ObjectNode copy = ((ObjectNode) inputContext).deepCopy();
    copy.remove("launchParameters");
    copy.remove("currentContext");

    JsonNode sharedContext = copy.get("sharedContext");
    if (sharedContext != null && sharedContext.isObject()) {
      ObjectNode shared = ((ObjectNode) sharedContext).deepCopy();
      shared.remove(List.of("steps", "current", "lastOutput", "initial"));
      if (shared.size() > 0) {
        copy.set("sharedContext", shared);
      } else {
        copy.remove("sharedContext");
      }
    }

    if (copy.size() == 0) {
      return null;
    }
    return copy;
  }

  private boolean hasContent(JsonNode node) {
    if (node == null || node.isNull()) {
      return false;
    }
    if (node.isValueNode()) {
      return !node.asText().isBlank();
    }
    return node.size() > 0;
  }

  private List<String> buildMemorySnapshots(
      FlowSession flowSession, List<MemoryReadInstruction> memoryReads) {
    if (memoryReads == null || memoryReads.isEmpty()) {
      return List.of();
    }
    List<String> messages = new ArrayList<>(memoryReads.size());
    for (MemoryReadInstruction instruction : memoryReads) {
      List<JsonNode> entries =
          flowMemoryService.history(flowSession.getId(), instruction.channel(), instruction.limit());
      if (!entries.isEmpty()) {
        messages.add(renderMemory(instruction.channel(), entries));
      }
    }
    return List.copyOf(messages);
  }

  private String renderMemory(String channel, List<JsonNode> entries) {
    JsonNode array = sanitizeMemoryEntries(entries);
    return "Shared memory channel '" + channel + "':\n" + array.toPrettyString();
  }

  private JsonNode sanitizeMemoryEntries(List<JsonNode> entries) {
    ArrayNode sanitized = objectMapper.createArrayNode();
    for (JsonNode entry : entries) {
      sanitized.add(sanitizeMemoryEntry(entry));
    }
    return sanitized;
  }

  private JsonNode sanitizeMemoryEntry(JsonNode entry) {
    if (entry == null) {
      return objectMapper.getNodeFactory().nullNode();
    }
    if (entry.isObject()) {
      ObjectNode copy = ((ObjectNode) entry).deepCopy();
      copy.remove(List.of("usage", "cost"));
      List<String> fieldNames = new ArrayList<>();
      copy.fieldNames().forEachRemaining(fieldNames::add);
      for (String field : fieldNames) {
        copy.set(field, sanitizeMemoryEntry(copy.get(field)));
      }
      if (copy.size() == 1 && copy.has("content")) {
        return copy.get("content");
      }
      return copy;
    }
    if (entry.isArray()) {
      ArrayNode copy = objectMapper.createArrayNode();
      entry.forEach(element -> copy.add(sanitizeMemoryEntry(element)));
      return copy;
    }
    return entry;
  }

  private List<com.aiadvent.backend.flow.domain.FlowMemoryVersion> applyMemoryWrites(
      UUID flowSessionId, List<MemoryWriteInstruction> writes, UUID stepId) {
    if (writes == null || writes.isEmpty()) {
      return List.of();
    }
    List<com.aiadvent.backend.flow.domain.FlowMemoryVersion> updates = new ArrayList<>(writes.size());
    for (MemoryWriteInstruction write : writes) {
      updates.add(flowMemoryService.append(flowSessionId, write.channel(), write.payload(), stepId));
    }
    return updates;
  }

  private RetryTemplate resolveRetryTemplate(
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
              throwable -> isRetryableStatus(throwable, retryableStatuses));
    } else {
      builder =
          builder.retryOn(
              throwable -> isRetryableStatus(throwable, null));
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

  private boolean isRetryableStatus(Throwable throwable, Set<Integer> retryableStatuses) {
    if (!(throwable instanceof WebClientResponseException webClientException)) {
      return false;
    }
    if (retryableStatuses == null || retryableStatuses.isEmpty()) {
      return true;
    }
    return retryableStatuses.contains(webClientException.getStatusCode().value());
  }

  private void logAttemptSuccess(RetryContext context, UUID flowSessionId, String providerId) {
    int attempt = context != null ? context.getRetryCount() + 1 : 1;
    if (attempt > 1) {
      log.info(
          "Agent invocation attempt {} succeeded for session {} using provider {}",
          attempt,
          flowSessionId,
          providerId);
    } else if (log.isDebugEnabled()) {
      log.debug(
          "Agent invocation attempt {} succeeded for session {} using provider {}",
          attempt,
          flowSessionId,
          providerId);
    }
  }

  private void logAttemptFailure(
      RetryContext context, UUID flowSessionId, String providerId, Throwable throwable) {
    if (throwable == null) {
      return;
    }
    int attempt = context != null ? context.getRetryCount() + 1 : 1;
    String message = throwable.getMessage();
    if (log.isDebugEnabled()) {
      log.debug(
          "Agent invocation attempt {} failed for session {} using provider {}: {}",
          attempt,
          flowSessionId,
          providerId,
          message,
          throwable);
    } else {
      log.info(
          "Agent invocation attempt {} failed for session {} using provider {}: {}",
          attempt,
          flowSessionId,
          providerId,
          message);
    }
  }

  private Usage extractUsage(ChatResponseMetadata metadata) {
    if (metadata == null) {
      return null;
    }
    return metadata.getUsage();
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
}
