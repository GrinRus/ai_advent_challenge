package com.aiadvent.backend.flow.memory;

import com.aiadvent.backend.chat.config.ChatMemoryProperties;
import com.aiadvent.backend.chat.memory.ChatMemorySummarizerService;
import com.aiadvent.backend.chat.memory.ChatMemorySummarizerService.SummarizationDecision;
import com.aiadvent.backend.chat.provider.ChatProviderService;
import com.aiadvent.backend.chat.token.TokenUsageEstimator;
import com.aiadvent.backend.chat.token.TokenUsageEstimator.Estimate;
import com.aiadvent.backend.chat.token.TokenUsageEstimator.EstimateRequest;
import com.aiadvent.backend.flow.domain.FlowMemorySummary;
import com.aiadvent.backend.flow.domain.FlowMemoryVersion;
import com.aiadvent.backend.flow.domain.FlowSession;
import com.aiadvent.backend.flow.persistence.AgentVersionRepository;
import com.aiadvent.backend.flow.persistence.FlowMemorySummaryRepository;
import com.aiadvent.backend.flow.persistence.FlowMemoryVersionRepository;
import com.aiadvent.backend.flow.persistence.FlowSessionRepository;
import com.aiadvent.backend.shared.text.SimpleLanguageDetector;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class FlowMemorySummarizerService {

  private static final String DEFAULT_CHANNEL = FlowMemoryChannels.CONVERSATION;
  private static final int SUMMARY_METADATA_SCHEMA_VERSION = 1;
  private static final int FAILURE_ALERT_THRESHOLD = 3;
  private static final int MIN_TAIL_MESSAGES = 4;
  private static final int DEFAULT_QUEUE_CAPACITY = 100;
  private static final AtomicInteger WORKER_SEQUENCE = new AtomicInteger();
  private static final Logger log = LoggerFactory.getLogger(FlowMemorySummarizerService.class);

  private final FlowSessionRepository flowSessionRepository;
  private final FlowMemoryVersionRepository flowMemoryVersionRepository;
  private final FlowMemorySummaryRepository flowMemorySummaryRepository;
  private final AgentVersionRepository agentVersionRepository;
  private final ChatMemorySummarizerService chatMemorySummarizerService;
  private final ChatMemoryProperties chatMemoryProperties;
  private final TokenUsageEstimator tokenUsageEstimator;
  private final ObjectMapper objectMapper;
  private final ChatProviderService chatProviderService;
  private final ThreadPoolExecutor dispatcherExecutor;
  private final Counter summaryCounter;
  private final Timer summaryTimer;
  private final Counter queueRejectedCounter;
  private final Counter summaryFailureCounter;
  private final Counter summaryFailureAlertCounter;
  private final AtomicInteger activeSummaries = new AtomicInteger();
  private final ConcurrentMap<UUID, AtomicInteger> failureCounts = new ConcurrentHashMap<>();

  public FlowMemorySummarizerService(
      FlowSessionRepository flowSessionRepository,
      FlowMemoryVersionRepository flowMemoryVersionRepository,
      FlowMemorySummaryRepository flowMemorySummaryRepository,
      AgentVersionRepository agentVersionRepository,
      ChatMemorySummarizerService chatMemorySummarizerService,
      ChatMemoryProperties chatMemoryProperties,
      TokenUsageEstimator tokenUsageEstimator,
      ObjectMapper objectMapper,
      ChatProviderService chatProviderService,
      MeterRegistry meterRegistry) {
    this.flowSessionRepository = Objects.requireNonNull(flowSessionRepository, "flowSessionRepository must not be null");
    this.flowMemoryVersionRepository =
        Objects.requireNonNull(flowMemoryVersionRepository, "flowMemoryVersionRepository must not be null");
    this.flowMemorySummaryRepository =
        Objects.requireNonNull(flowMemorySummaryRepository, "flowMemorySummaryRepository must not be null");
    this.agentVersionRepository =
        Objects.requireNonNull(agentVersionRepository, "agentVersionRepository must not be null");
    this.chatMemorySummarizerService =
        Objects.requireNonNull(chatMemorySummarizerService, "chatMemorySummarizerService must not be null");
    this.chatMemoryProperties =
        Objects.requireNonNull(chatMemoryProperties, "chatMemoryProperties must not be null");
    this.tokenUsageEstimator = Objects.requireNonNull(tokenUsageEstimator, "tokenUsageEstimator must not be null");
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    this.chatProviderService = Objects.requireNonNull(chatProviderService, "chatProviderService must not be null");
    this.dispatcherExecutor = buildDispatcherExecutor(chatMemoryProperties);
    if (meterRegistry != null) {
      this.summaryCounter =
          Counter.builder("flow_summary_runs_total")
              .description("Number of flow memory summarisation executions")
              .register(meterRegistry);
      this.summaryTimer =
          Timer.builder("flow_summary_duration_seconds")
              .description("Latency of flow memory summarisation")
              .register(meterRegistry);
      this.queueRejectedCounter =
          Counter.builder("flow_summary_queue_rejections_total")
              .description("Number of flow summaries skipped because workers are busy")
              .register(meterRegistry);
      this.summaryFailureCounter =
          Counter.builder("flow_summary_failures_total")
              .description("Number of failed flow memory summarisation attempts")
              .register(meterRegistry);
      this.summaryFailureAlertCounter =
          Counter.builder("flow_summary_failure_alerts_total")
              .description("Number of times flow summary failures reached alert threshold")
              .register(meterRegistry);
      Gauge.builder("flow_summary_active_jobs", activeSummaries, AtomicInteger::get)
          .description("Number of flow summary jobs currently executing")
          .register(meterRegistry);
      Gauge.builder("flow_summary_queue_size", dispatcherExecutor, exec -> exec.getQueue().size())
          .description("Number of flow summary jobs waiting for execution")
          .register(meterRegistry);
    } else {
      this.summaryCounter = null;
      this.summaryTimer = null;
      this.queueRejectedCounter = null;
      this.summaryFailureCounter = null;
      this.summaryFailureAlertCounter = null;
    }
  }

  private ThreadPoolExecutor buildDispatcherExecutor(ChatMemoryProperties chatMemoryProperties) {
    ChatMemoryProperties.SummarizationProperties properties = chatMemoryProperties.getSummarization();
    int queueCapacity =
        properties != null && properties.getMaxQueueSize() > 0
            ? properties.getMaxQueueSize()
            : DEFAULT_QUEUE_CAPACITY;
    queueCapacity = Math.max(1, queueCapacity);
    int workerCount =
        properties != null && properties.getMaxConcurrentSummaries() > 0
            ? properties.getMaxConcurrentSummaries()
            : 1;
    workerCount = Math.max(1, workerCount);
    ThreadFactory threadFactory =
        runnable -> {
          Thread thread = new Thread(runnable);
          thread.setName("flow-summary-" + WORKER_SEQUENCE.incrementAndGet());
          thread.setDaemon(true);
          return thread;
        };
    return new ThreadPoolExecutor(
        workerCount,
        workerCount,
        0L,
        TimeUnit.MILLISECONDS,
        new LinkedBlockingQueue<>(queueCapacity),
        threadFactory,
        new ThreadPoolExecutor.AbortPolicy());
  }

  public boolean supportsChannel(String channel) {
    return StringUtils.hasText(channel) && DEFAULT_CHANNEL.equalsIgnoreCase(channel.trim());
  }

  @Transactional(readOnly = true)
  public Optional<PreflightResult> preflight(
      UUID flowSessionId,
      String channel,
      String providerId,
      String modelId,
      String forthcomingUserMessage) {
    if (!chatMemorySummarizerService.isEnabled()
        || flowSessionId == null
        || !supportsChannel(channel)
        || !StringUtils.hasText(providerId)
        || !StringUtils.hasText(modelId)) {
      return Optional.empty();
    }

    FlowSession session = flowSessionRepository.findById(flowSessionId).orElse(null);
    if (session == null) {
      return Optional.empty();
    }
    return preparePlan(session, channel, providerId, modelId, forthcomingUserMessage, false);
  }

  @Transactional
  public void forceSummarize(
      UUID flowSessionId, String providerId, String modelId, Collection<String> channels) {
    if (flowSessionId == null || !StringUtils.hasText(providerId) || !StringUtils.hasText(modelId)) {
      throw new IllegalArgumentException("flowSessionId, providerId and modelId must not be blank");
    }
    if (!chatMemorySummarizerService.isEnabled()) {
      log.warn("Flow summariser is disabled but manual rebuild was requested; continuing anyway");
    }
    FlowSession session =
        flowSessionRepository
            .findById(flowSessionId)
            .orElseThrow(() -> new IllegalArgumentException("Flow session not found: " + flowSessionId));

    Set<String> targetChannels = new LinkedHashSet<>();
    if (channels != null) {
      channels.stream()
          .filter(StringUtils::hasText)
          .map(String::trim)
          .filter(this::supportsChannel)
          .forEach(targetChannels::add);
    }
    if (targetChannels.isEmpty()) {
      targetChannels.add(DEFAULT_CHANNEL);
    }

    for (String channel : targetChannels) {
      log.info(
          "Manual flow summary rebuild requested for session {} channel {} via provider {}:{}",
          session.getId(),
          channel,
          providerId,
          modelId);
      flowMemorySummaryRepository.deleteByFlowSessionAndChannel(session, channel);
      preparePlan(session, channel, providerId, modelId, null, true)
          .ifPresent(this::executeSummary);
    }
  }

  private Optional<PreflightResult> preparePlan(
      FlowSession session,
      String channel,
      String providerId,
      String modelId,
      String forthcomingUserMessage,
      boolean force) {
    long summarizedUntil =
        flowMemorySummaryRepository
            .findFirstByFlowSessionAndChannelOrderBySourceVersionEndDesc(session, channel)
            .map(FlowMemorySummary::getSourceVersionEnd)
            .orElse(0L);

    List<FlowMemoryVersion> versions =
        summarizedUntil > 0
            ? flowMemoryVersionRepository
                .findByFlowSessionAndChannelAndVersionGreaterThanOrderByVersionAsc(session, channel, summarizedUntil)
            : flowMemoryVersionRepository.findByFlowSessionAndChannelOrderByVersionAsc(session, channel);

    if (versions.isEmpty()) {
      return Optional.empty();
    }

    List<FlowTranscriptEntry> orderedEntries = versions.stream().map(this::toEntry).toList();
    int tailCount = resolveTailCount(orderedEntries.size());
    if (tailCount >= orderedEntries.size()) {
      return Optional.empty();
    }

    List<Message> transcript = orderedEntries.stream().map(this::toMessage).filter(Objects::nonNull).toList();
    if (transcript.isEmpty()) {
      return Optional.empty();
    }

    List<Message> evaluated = new ArrayList<>(transcript);
    if (StringUtils.hasText(forthcomingUserMessage)) {
      evaluated.add(UserMessage.builder().text(forthcomingUserMessage.trim()).build());
    }

    String tokenizer = resolveTokenizer(providerId, modelId);
    int estimatedTokens = estimateTokens(providerId, modelId, tokenizer, evaluated);

    if (!force && estimatedTokens <= chatMemorySummarizerService.triggerTokenLimit()) {
      return Optional.empty();
    }

    int summaryCount = orderedEntries.size() - tailCount;
    if (summaryCount <= 0) {
      return Optional.empty();
    }
    List<FlowTranscriptEntry> toSummarize = orderedEntries.subList(0, summaryCount);

    SummarizationDecision decision =
        new SummarizationDecision(
            true,
            estimatedTokens,
            chatMemorySummarizerService.triggerTokenLimit(),
            chatMemorySummarizerService.targetTokenCount());

    return Optional.of(
        new PreflightResult(
            session.getId(), channel, providerId, modelId, tokenizer, toSummarize, decision));
  }

  @Transactional
  public void processPreflightResult(PreflightResult result) {
    if (result == null || result.entries().isEmpty()) {
      return;
    }
    try {
      dispatcherExecutor.execute(() -> executeSummary(result));
    } catch (RejectedExecutionException exception) {
      recordQueueRejection();
      log.warn(
          "Flow summarisation queue is full; skipping session {} channel {}",
          result.sessionId(),
          result.channel());
    }
  }

  private void persistSummary(PreflightResult result, String summaryText) {
    FlowSession session =
        flowSessionRepository
            .findByIdForUpdate(result.sessionId())
            .orElseThrow(() -> new IllegalStateException("Flow session not found during summarisation"));

    long startVersion = result.entries().get(0).version();
    long endVersion = result.entries().get(result.entries().size() - 1).version();

    FlowMemorySummary summary =
        new FlowMemorySummary(session, result.channel(), startVersion, endVersion, summaryText);

    String lastStepId =
        result.entries().stream()
            .map(FlowTranscriptEntry::stepId)
            .filter(StringUtils::hasText)
            .reduce((first, second) -> second)
            .orElse(null);
    summary.setStepId(lastStepId);

    Integer minAttempt =
        result.entries().stream()
            .map(FlowTranscriptEntry::stepAttempt)
            .filter(Objects::nonNull)
            .min(Comparator.naturalOrder())
            .orElse(null);
    Integer maxAttempt =
        result.entries().stream()
            .map(FlowTranscriptEntry::stepAttempt)
            .filter(Objects::nonNull)
            .max(Comparator.naturalOrder())
            .orElse(null);
    summary.setAttemptStart(minAttempt);
    summary.setAttemptEnd(maxAttempt);

    summary.setTokenCount(
        Long.valueOf(
            Math.max(
                0,
                estimateTokens(
                    result.providerId(),
                    result.modelId(),
                    result.tokenizerOverride(),
                    result.entries().stream().map(this::toMessage).filter(Objects::nonNull).toList()))));

    summary.setLanguage(SimpleLanguageDetector.detectLanguage(summaryText));

    UUID agentVersionId = resolveLatestAgentVersionId(result.entries());
    if (agentVersionId != null) {
      agentVersionRepository.findById(agentVersionId).ifPresent(summary::setAgentVersion);
    }

    summary.setMetadata(buildMetadata(result.entries(), agentVersionId));

    flowMemorySummaryRepository.save(summary);
    log.info(
        "Summarised flow session {} channel {} covering versions {}-{}",
        result.sessionId(),
        result.channel(),
        startVersion,
        endVersion);
  }

  private ObjectNode buildMetadata(List<FlowTranscriptEntry> entries, UUID agentVersionId) {
    ObjectNode metadata = objectMapper.createObjectNode();
    metadata.put("schemaVersion", SUMMARY_METADATA_SCHEMA_VERSION);
    metadata.put("summary", true);
    metadata.put("entriesSummarized", entries.size());
    if (agentVersionId != null) {
      metadata.put("agentVersionId", agentVersionId.toString());
    }
    metadata.put("generatedAt", Instant.now().toString());
    return metadata;
  }

  private FlowTranscriptEntry toEntry(FlowMemoryVersion version) {
    return new FlowTranscriptEntry(
        version.getVersion(),
        version.getSourceType(),
        version.getStepId(),
        version.getStepAttempt(),
        version.getAgentVersionId(),
        version.getData());
  }

  private Message toMessage(FlowTranscriptEntry entry) {
    String text = formatPayload(entry.payload());
    MessageType type =
        entry.sourceType() != null ? mapType(entry.sourceType()) : MessageType.SYSTEM;
    return switch (type) {
      case USER -> UserMessage.builder().text(text).build();
      case ASSISTANT -> AssistantMessage.builder().content(text).build();
      case SYSTEM -> SystemMessage.builder().text(text).build();
      default -> SystemMessage.builder().text(text).build();
    };
  }

  private MessageType mapType(FlowMemorySourceType sourceType) {
    if (sourceType == null) {
      return MessageType.SYSTEM;
    }
    return switch (sourceType) {
      case USER_INPUT -> MessageType.USER;
      case AGENT_OUTPUT -> MessageType.ASSISTANT;
      default -> MessageType.SYSTEM;
    };
  }

  private String buildPrompt(List<FlowTranscriptEntry> entries) {
    String transcript =
        entries.stream()
            .map(
                entry ->
                    mapType(entry.sourceType()).name().toLowerCase()
                        + ": "
                        + formatPayload(entry.payload()))
            .reduce((left, right) -> left + "\n" + right)
            .orElse("");
    return
        """
        Суммируй журнал общения пользователя и агента. Сохрани факты, упомянутые шаги, решения и открытые вопросы.
        Добавь списком ключевые риски и следующие шаги, если они встречаются.

        Журнал:
        %s
        """
            .formatted(transcript);
  }

  private void recordQueueRejection() {
    if (queueRejectedCounter != null) {
      queueRejectedCounter.increment();
    }
  }

  private void recordSummaryRunMetrics(UUID sessionId, long durationNanos) {
    if (summaryCounter != null) {
      summaryCounter.increment();
    }
    if (summaryTimer != null) {
      summaryTimer.record(durationNanos, TimeUnit.NANOSECONDS);
    }
    resetFailures(sessionId);
  }

  private void recordFailure(UUID sessionId, String reason, Throwable throwable) {
    if (summaryFailureCounter != null) {
      summaryFailureCounter.increment();
    }
    if (sessionId == null) {
      return;
    }
    int current =
        failureCounts.computeIfAbsent(sessionId, key -> new AtomicInteger()).incrementAndGet();
    if (current >= FAILURE_ALERT_THRESHOLD && summaryFailureAlertCounter != null) {
      summaryFailureAlertCounter.increment();
      log.error(
          "Flow summarisation repeatedly failed for session {} ({} consecutive errors): {}",
          sessionId,
          current,
          reason,
          throwable);
    } else {
      log.warn(
          "Flow summarisation failed for session {} ({} consecutive errors): {}",
          sessionId,
          current,
          reason,
          throwable);
    }
  }

  private void resetFailures(UUID sessionId) {
    if (sessionId != null) {
      failureCounts.remove(sessionId);
    }
  }

  private String formatPayload(JsonNode payload) {
    if (payload == null || payload.isNull()) {
      return "(empty)";
    }
    if (payload.isTextual()) {
      return payload.asText();
    }
    if (payload.isObject()) {
      ObjectNode object = (ObjectNode) payload;
      if (object.hasNonNull("prompt")) {
        StringBuilder builder = new StringBuilder(object.get("prompt").asText());
        if (object.hasNonNull("context")) {
          builder.append(" context=").append(object.get("context").toString());
        }
        return builder.toString();
      }
    }
    try {
      return objectMapper.writeValueAsString(payload);
    } catch (JsonProcessingException exception) {
      return payload.toString();
    }
  }

  private int estimateTokens(
      String providerId, String modelId, String tokenizerOverride, List<Message> messages) {
    if (tokenUsageEstimator == null || messages.isEmpty()) {
      return 0;
    }
    String prompt = messagesToPrompt(messages);
    Estimate estimate =
        tokenUsageEstimator.estimate(
            new EstimateRequest(providerId, modelId, tokenizerOverride, prompt, null));
    return estimate != null ? estimate.totalTokens() : 0;
  }

  private String messagesToPrompt(List<Message> messages) {
    return messages.stream()
        .map(
            message ->
                message.getMessageType().name().toLowerCase()
                    + ": "
                    + StringUtils.trimWhitespace(message.getText()))
        .reduce((left, right) -> left + "\n" + right)
        .orElse("");
  }

  private String resolveTokenizer(String providerId, String modelId) {
    try {
      var modelConfig = chatProviderService.model(providerId, modelId);
      if (modelConfig != null && modelConfig.getUsage() != null) {
        return modelConfig.getUsage().getFallbackTokenizer();
      }
    } catch (RuntimeException exception) {
      log.debug(
          "Failed to resolve tokenizer for flow summarisation (provider={}, model={}): {}",
          providerId,
          modelId,
          exception.getMessage());
    }
    return null;
  }

  private int resolveTailCount(int totalEntries) {
    if (totalEntries <= 1) {
      return 0;
    }
    int dynamicTail = Math.max(MIN_TAIL_MESSAGES, totalEntries / 2);
    int boundedTail = Math.min(dynamicTail, totalEntries - 1);
    return Math.max(1, boundedTail);
  }

  public record PreflightResult(
      UUID sessionId,
      String channel,
      String providerId,
      String modelId,
      String tokenizerOverride,
      List<FlowTranscriptEntry> entries,
      SummarizationDecision decision) {}

  private record FlowTranscriptEntry(
      long version,
      FlowMemorySourceType sourceType,
      String stepId,
      Integer stepAttempt,
      UUID agentVersionId,
      JsonNode payload) {}

  private UUID resolveLatestAgentVersionId(List<FlowTranscriptEntry> entries) {
    return entries.stream()
        .map(FlowTranscriptEntry::agentVersionId)
        .filter(Objects::nonNull)
        .reduce((first, second) -> second)
        .orElse(null);
  }

  private void executeSummary(PreflightResult result) {
    if (result == null || result.entries().isEmpty()) {
      return;
    }
    log.info(
        "Flow summarisation scheduled for session {} channel {} (estimated {} tokens, trigger {}).",
        result.sessionId(),
        result.channel(),
        result.decision().estimatedTokens(),
        result.decision().triggerTokenLimit());
    boolean slotAcquired = false;
    try {
      slotAcquired = acquireSummarizerSlot();
      if (!slotAcquired) {
        log.warn("Flow summarisation skipped for session {} because workers are shutting down", result.sessionId());
        return;
      }
      activeSummaries.incrementAndGet();
      long started = System.nanoTime();
      String prompt = buildPrompt(result.entries());
      if (!StringUtils.hasText(prompt)) {
        resetFailures(result.sessionId());
        return;
      }
      Optional<String> summaryText =
          chatMemorySummarizerService.summarizeTranscript(result.sessionId(), prompt, "flow");
      if (summaryText.isEmpty()) {
        recordFailure(result.sessionId(), "empty response", null);
        return;
      }
      persistSummary(result, summaryText.get());
      chatMemorySummarizerService.recordSummaryRun(System.nanoTime() - started);
      recordSummaryRunMetrics(result.sessionId(), System.nanoTime() - started);
    } catch (InterruptedException interruptedException) {
      Thread.currentThread().interrupt();
    } catch (Exception exception) {
      recordFailure(result.sessionId(), exception.getMessage(), exception);
    } finally {
      if (slotAcquired) {
        chatMemorySummarizerService.releaseSlot();
        activeSummaries.decrementAndGet();
      }
    }
  }

  private boolean acquireSummarizerSlot() throws InterruptedException {
    while (!dispatcherExecutor.isShutdown()) {
      if (chatMemorySummarizerService.tryAcquireSlot()) {
        return true;
      }
      Thread.sleep(50L);
    }
    return false;
  }

  @PreDestroy
  void shutdown() {
    dispatcherExecutor.shutdownNow();
  }
}
