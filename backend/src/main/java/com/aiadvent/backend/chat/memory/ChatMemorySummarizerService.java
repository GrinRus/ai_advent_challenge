package com.aiadvent.backend.chat.memory;

import com.aiadvent.backend.chat.config.ChatMemoryProperties;
import com.aiadvent.backend.chat.domain.ChatMemorySummary;
import com.aiadvent.backend.chat.domain.ChatSession;
import com.aiadvent.backend.chat.persistence.ChatMemorySummaryRepository;
import com.aiadvent.backend.chat.persistence.ChatSessionRepository;
import com.aiadvent.backend.chat.provider.ChatProviderService;
import com.aiadvent.backend.chat.provider.model.ChatProviderSelection;
import com.aiadvent.backend.chat.provider.model.ChatRequestOverrides;
import com.aiadvent.backend.chat.token.TokenUsageEstimator;
import com.aiadvent.backend.chat.token.TokenUsageEstimator.Estimate;
import com.aiadvent.backend.chat.token.TokenUsageEstimator.EstimateRequest;
import com.aiadvent.backend.shared.text.SimpleLanguageDetector;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Coordinates LLM-driven summarisation of chat history when the assembled prompt exceeds the
 * configured threshold. The service currently focuses on assessing whether summarisation is needed
 * and prepares contextual metadata required for downstream execution.
 */
@Service
@Slf4j
public class ChatMemorySummarizerService {

  private static final String SUMMARISATION_METRIC_NAME = "chat_summary_runs_total";
  private static final String SUMMARISATION_DURATION_METRIC = "chat_summary_duration_seconds";
  private static final String SUMMARISATION_FAILURE_METRIC = "chat_summary_failures_total";
  private static final String SUMMARISATION_ALERT_METRIC = "chat_summary_failure_alerts_total";
  private static final String SUMMARY_QUEUE_METRIC = "chat_summary_queue_size";
  private static final String SUMMARY_QUEUE_REJECTIONS_METRIC = "chat_summary_queue_rejections_total";
  private static final int FAILURE_ALERT_THRESHOLD = 3;
  private static final AtomicInteger WORKER_SEQUENCE = new AtomicInteger();
  private static final int SUMMARY_METADATA_SCHEMA_VERSION = 1;

  private final ChatMemoryProperties properties;
  private final TokenUsageEstimator tokenUsageEstimator;
  private final ChatMemorySummaryRepository summaryRepository;
  private final ChatSessionRepository chatSessionRepository;
  private final ChatProviderService chatProviderService;
  private final ChatMemoryRepository chatMemoryRepository;
  private final ObjectMapper objectMapper;
  private final Semaphore concurrencyLimiter;
  private final ThreadPoolExecutor summarizationExecutor;
  private final Counter summaryCounter;
  private final Timer summaryTimer;
  private final Counter skippedCounter;
  private final Counter tokensSavedCounter;
  private final Counter summaryFailureCounter;
  private final Counter summaryFailureAlertCounter;
  private final Counter queueRejectedCounter;
  private final ConcurrentMap<UUID, AtomicInteger> failureCounts = new ConcurrentHashMap<>();

  private final String summarizerProviderId;
  private final String summarizerModelId;

  public ChatMemorySummarizerService(
      ChatMemoryProperties properties,
      TokenUsageEstimator tokenUsageEstimator,
      ChatMemorySummaryRepository summaryRepository,
      ChatSessionRepository chatSessionRepository,
      ChatProviderService chatProviderService,
      ChatMemoryRepository chatMemoryRepository,
      MeterRegistry meterRegistry,
      ObjectMapper objectMapper) {
    this.properties = Objects.requireNonNull(properties, "properties must not be null");
    this.tokenUsageEstimator =
        Objects.requireNonNull(tokenUsageEstimator, "tokenUsageEstimator must not be null");
    this.summaryRepository =
        Objects.requireNonNull(summaryRepository, "summaryRepository must not be null");
    this.chatSessionRepository =
        Objects.requireNonNull(chatSessionRepository, "chatSessionRepository must not be null");
    this.chatProviderService =
        Objects.requireNonNull(chatProviderService, "chatProviderService must not be null");
    this.chatMemoryRepository =
        Objects.requireNonNull(chatMemoryRepository, "chatMemoryRepository must not be null");
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");

    ChatMemoryProperties.SummarizationProperties summarization = properties.getSummarization();
    int concurrency =
        summarization != null ? Math.max(1, summarization.getMaxConcurrentSummaries()) : 1;
    int queueSize =
        summarization != null
            ? Math.max(concurrency, summarization.getMaxQueueSize())
            : concurrency * 2;
    this.concurrencyLimiter = new Semaphore(concurrency, true);
    this.summarizationExecutor = buildExecutor(concurrency, queueSize);

    if (meterRegistry != null) {
      this.summaryCounter =
          Counter.builder(SUMMARISATION_METRIC_NAME)
              .description("Number of chat memory summarisation executions")
              .register(meterRegistry);
      this.summaryTimer =
          Timer.builder(SUMMARISATION_DURATION_METRIC)
              .description("Latency of chat memory summarisation")
              .register(meterRegistry);
      this.skippedCounter =
          Counter.builder("chat_summary_skipped_total")
              .description("Number of times summarisation was skipped after preflight assessment")
              .register(meterRegistry);
      this.tokensSavedCounter =
          Counter.builder("chat_summary_tokens_saved_total")
              .description("Approximate number of tokens saved by summarisation")
              .register(meterRegistry);
      this.summaryFailureCounter =
          Counter.builder(SUMMARISATION_FAILURE_METRIC)
              .description("Number of failed chat memory summarisation attempts")
              .register(meterRegistry);
      this.summaryFailureAlertCounter =
          Counter.builder(SUMMARISATION_ALERT_METRIC)
              .description("Number of times summarisation failures reached alert threshold")
              .register(meterRegistry);
      Gauge.builder(SUMMARY_QUEUE_METRIC, summarizationExecutor, exec -> exec.getQueue().size())
          .description("Number of chat memory summarisation jobs waiting in queue")
          .register(meterRegistry);
      this.queueRejectedCounter =
          Counter.builder(SUMMARY_QUEUE_REJECTIONS_METRIC)
              .description("Number of chat memory summarisation jobs dropped due to full queue")
              .register(meterRegistry);
    } else {
      this.summaryCounter = null;
      this.summaryTimer = null;
      this.skippedCounter = null;
      this.tokensSavedCounter = null;
      this.summaryFailureCounter = null;
      this.summaryFailureAlertCounter = null;
      this.queueRejectedCounter = null;
    }

    String configuredModel =
        summarization != null ? StringUtils.trimWhitespace(summarization.getModel()) : null;
    if (!StringUtils.hasText(configuredModel) || !configuredModel.contains(":")) {
      this.summarizerProviderId = null;
      this.summarizerModelId = null;
    } else {
      String[] tokens = configuredModel.split(":", 2);
      this.summarizerProviderId = tokens[0].toLowerCase();
      this.summarizerModelId = tokens[1];
    }
  }

  @PreDestroy
  void shutdown() {
    summarizationExecutor.shutdownNow();
  }

  private ThreadPoolExecutor buildExecutor(int concurrency, int queueSize) {
    BlockingQueue<Runnable> queue = new ArrayBlockingQueue<>(queueSize, true);
    ThreadFactory factory =
        runnable -> {
          Thread thread = new Thread(runnable);
          thread.setName("chat-summary-" + WORKER_SEQUENCE.incrementAndGet());
          thread.setDaemon(true);
          return thread;
        };
    return new ThreadPoolExecutor(concurrency, concurrency, 0L, TimeUnit.MILLISECONDS, queue, factory);
  }

  public boolean isEnabled() {
    return properties.getSummarization() != null && properties.getSummarization().isEnabled();
  }

  public int triggerTokenLimit() {
    return properties.getSummarization() != null
        ? properties.getSummarization().getTriggerTokenLimit()
        : Integer.MAX_VALUE;
  }

  public int targetTokenCount() {
    return properties.getSummarization() != null
        ? properties.getSummarization().getTargetTokenCount()
        : triggerTokenLimit();
  }

  public Optional<SummarizationDecision> evaluate(SummarizationInput input) {
    if (!isEnabled()) {
      return Optional.empty();
    }
    Objects.requireNonNull(input, "input must not be null");
    int estimatedTokens = estimatePromptTokens(input);
    boolean required = estimatedTokens > triggerTokenLimit();
    SummarizationDecision decision =
        new SummarizationDecision(required, estimatedTokens, triggerTokenLimit(), targetTokenCount());
    if (!required && skippedCounter != null) {
      skippedCounter.increment();
    }
    return Optional.of(decision);
  }

  public Optional<ChatProviderSelection> summarizerSelection() {
    if (!StringUtils.hasText(summarizerProviderId) || !StringUtils.hasText(summarizerModelId)) {
      return Optional.empty();
    }
    try {
      return Optional.of(chatProviderService.resolveSelection(summarizerProviderId, summarizerModelId));
    } catch (RuntimeException ex) {
      log.warn(
          "Summarisation model {}:{} is not available in provider registry: {}",
          summarizerProviderId,
          summarizerModelId,
          ex.getMessage());
      return Optional.empty();
    }
  }

  public Optional<ChatOptionsEnvelope> summarizerOptions() {
    return summarizerSelection()
        .map(
            selection ->
                new ChatOptionsEnvelope(
                    selection, chatProviderService.buildOptions(selection, summarizerOverrides())));
  }

  public Optional<String> summarizeTranscript(UUID correlationId, String prompt, String scope) {
    if (!StringUtils.hasText(prompt)) {
      return Optional.empty();
    }
    Optional<ChatOptionsEnvelope> options = summarizerOptions();
    if (options.isEmpty()) {
      log.warn("Summarisation model is not configured, skipping {} scope request", scope);
      return Optional.empty();
    }
    try {
      String summary = invokeSummarizer(options.get(), prompt);
      if (!StringUtils.hasText(summary)) {
        recordFailure(correlationId, scope, "Summarisation returned empty response");
        return Optional.empty();
      }
      resetFailures(correlationId);
      return Optional.of(summary.trim());
    } catch (SummarizationException exception) {
      recordFailure(correlationId, scope, exception);
      return Optional.empty();
    }
  }

  private ChatRequestOverrides summarizerOverrides() {
    // Summaries favour determinism and brevity.
    return new ChatRequestOverrides(0.2d, null, 512);
  }

  private int estimatePromptTokens(SummarizationInput input) {
    if (tokenUsageEstimator == null) {
      return 0;
    }
    String promptPayload = toPromptPayload(input.messages());
    Estimate estimate =
        tokenUsageEstimator.estimate(
            new EstimateRequest(
                input.targetProviderId(),
                input.targetModelId(),
                input.tokenizerOverride(),
                promptPayload,
                null));
    if (estimate == null) {
      return 0;
    }
    return estimate.totalTokens();
  }

  private String toPromptPayload(List<Message> messages) {
    if (messages == null || messages.isEmpty()) {
      return "";
    }
    return messages.stream()
        .map(
            message ->
                message.getMessageType().name().toLowerCase()
                    + ": "
                    + StringUtils.trimWhitespace(message.getText()))
        .collect(Collectors.joining("\n"));
  }

  public boolean tryAcquireSlot() {
    if (concurrencyLimiter.tryAcquire()) {
      return true;
    }
    log.debug("Summarisation concurrency limit reached, skipping slot acquisition");
    return false;
  }

  public void releaseSlot() {
    concurrencyLimiter.release();
  }

  public void recordSummaryRun(long durationNanos) {
    if (summaryCounter != null) {
      summaryCounter.increment();
    }
    if (summaryTimer != null) {
      summaryTimer.record(durationNanos, TimeUnit.NANOSECONDS);
    }
  }

  public Optional<SummarizerModelInfo> summarizerModel() {
    if (!StringUtils.hasText(summarizerProviderId) || !StringUtils.hasText(summarizerModelId)) {
      return Optional.empty();
    }
    return Optional.of(new SummarizerModelInfo(summarizerProviderId, summarizerModelId));
  }

  public void processPreflightResult(PreflightResult result) {
    if (result == null || !result.decision().shouldSummarize()) {
      return;
    }
    log.info(
        "Summarisation scheduled for session {} using provider {}:{} (estimated {} tokens, trigger {}).",
        result.sessionId(),
        result.providerId(),
        result.modelId(),
        result.decision().estimatedTokens(),
        result.decision().triggerTokenLimit());
    if (!enqueueSummarization(result)) {
      log.warn(
          "Summarisation queue is full (session={}), skipping request",
          result.sessionId());
      recordFailure(result.sessionId(), "chat", "Summarisation queue saturated");
    }
  }

  public void forceSummarize(UUID sessionId, String providerId, String modelId) {
    if (sessionId == null || !StringUtils.hasText(providerId) || !StringUtils.hasText(modelId)) {
      throw new IllegalArgumentException("sessionId, providerId and modelId must not be blank");
    }
    log.info(
        "Manual resummarisation requested for session {} using provider {} model {}",
        sessionId,
        providerId,
        modelId);
    List<Message> history = chatMemoryRepository.findByConversationId(sessionId.toString());
    if (history == null || history.isEmpty()) {
      log.info("No chat history to summarise for session {}", sessionId);
      return;
    }
    SummarizationInput input =
        new SummarizationInput(providerId, modelId, history, null);
    SummarizationDecision decision =
        new SummarizationDecision(true, estimatePromptTokens(input), triggerTokenLimit(), targetTokenCount());
    processPreflightResult(new PreflightResult(sessionId, providerId, modelId, history, decision));
  }

  private void summarizeConversation(PreflightResult result) {
    List<Message> conversation = result.messages();
    if (conversation == null || conversation.size() < 2) {
      return;
    }

    List<Message> transcript =
        conversation.stream()
            .filter(message -> !isSummaryMessage(message))
            .collect(Collectors.toList());

    if (transcript.size() < 2) {
      return;
    }

    ChatSession session =
        chatSessionRepository
            .findById(result.sessionId())
            .orElseThrow(() -> new IllegalStateException("Chat session not found during summarisation"));

    int totalMessages = transcript.size();
    int summaryCount = computeSummaryCount(totalMessages);
    if (summaryCount <= 0) {
      return;
    }
    List<Message> toSummarize = new ArrayList<>(transcript.subList(0, summaryCount));
    List<Message> tail = new ArrayList<>(transcript.subList(summaryCount, transcript.size()));

    String prompt = buildSummarisationPrompt(toSummarize);
    if (!StringUtils.hasText(prompt)) {
      return;
    }

    Optional<String> summaryText =
        summarizeTranscript(session.getId(), prompt, "chat");
    if (summaryText.isEmpty()) {
      return;
    }

    persistSummary(session, summaryText.get(), summaryCount);
    chatMemoryRepository.saveAll(session.getId().toString(), tail);
    log.info(
        "Summarised chat session {}: messages summarised={}, remaining={}, tokensEstimate={}",
        session.getId(),
        summaryCount,
        tail.size(),
        result.decision().estimatedTokens());
  }

  private void persistSummary(ChatSession session, String summaryText, int summaryCount) {
    int startOrder = session.getSummaryUntilOrder() + 1;
    int endOrder = startOrder + summaryCount - 1;
    ChatMemorySummary entity = new ChatMemorySummary(session, startOrder, endOrder, summaryText);
    Long estimatedTokens = estimateSummaryTokens(summaryText);
    if (estimatedTokens != null) {
      entity.setTokenCount(estimatedTokens);
    }
    entity.setLanguage(SimpleLanguageDetector.detectLanguage(summaryText));
    entity.setMetadata(buildSummaryMetadata(startOrder, endOrder));
    summaryRepository.save(entity);
    session.setSummaryUntilOrder(endOrder);
    session.setSummaryMetadata(buildSessionMetadata(endOrder));
    chatSessionRepository.save(session);
  }

  private Long estimateSummaryTokens(String summaryText) {
    if (tokenUsageEstimator == null || !StringUtils.hasText(summaryText)) {
      return null;
    }
    if (!StringUtils.hasText(summarizerProviderId) || !StringUtils.hasText(summarizerModelId)) {
      return null;
    }
    try {
      TokenUsageEstimator.Estimate estimate =
          tokenUsageEstimator.estimate(
              new TokenUsageEstimator.EstimateRequest(
                  summarizerProviderId, summarizerModelId, null, null, summaryText));
      if (estimate != null && estimate.hasUsage()) {
        return Long.valueOf(Math.max(0, estimate.totalTokens()));
      }
    } catch (RuntimeException exception) {
      log.debug("Failed to estimate summary token count", exception);
    }
    return null;
  }

  String buildSummarisationPrompt(List<Message> messages) {
    if (messages == null || messages.isEmpty()) {
      return "";
    }
    String transcript =
        messages.stream()
            .map(
                message ->
                    message.getMessageType().name().toLowerCase()
                        + ": "
                        + StringUtils.trimWhitespace(message.getText()))
            .collect(Collectors.joining("\n"));
    return
        """
        Сформируй краткое, но информативное summary диалога. Выдели:
        1. Ключевые факты и решения.
        2. Незакрытые вопросы или риски.
        3. Следующие шаги, если они обсуждались.

        Диалог:
        %s
        """
            .formatted(transcript);
  }

  private String invokeSummarizer(ChatOptionsEnvelope envelope, String prompt) {
    long delayMillis = 250L;
    int maxAttempts = 3;
    for (int attempt = 1; attempt <= maxAttempts; attempt++) {
      try {
        ChatResponse response =
            chatProviderService
                .statelessChatClient(envelope.selection().providerId())
                .prompt()
                .system("Ты assistant, который делает краткие summary диалогов. Пиши на языке источника.")
                .user(prompt)
                .options(envelope.options())
                .call()
                .chatResponse();
        return extractContent(response);
      } catch (Exception exception) {
        if (attempt >= maxAttempts) {
          throw new SummarizationException(
              "Summarisation request failed after " + attempt + " attempts", exception);
        }
        log.debug("Summarisation attempt {} failed: {}", attempt, exception.getMessage());
        try {
          Thread.sleep(delayMillis);
        } catch (InterruptedException interruptedException) {
          Thread.currentThread().interrupt();
          throw new SummarizationException("Summarisation interrupted", interruptedException);
        }
        delayMillis = Math.min(delayMillis * 2, 2000L);
      }
    }
    throw new SummarizationException("Summarisation was aborted before completion", null);
  }

  String extractContent(ChatResponse response) {
    if (response == null || response.getResults() == null) {
      return null;
    }
    return response.getResults().stream()
        .map(Generation::getOutput)
        .filter(Objects::nonNull)
        .map(output -> output.getText())
        .filter(StringUtils::hasText)
        .findFirst()
        .orElse(null);
  }

  public Optional<PreflightResult> preflight(
      UUID sessionId, String providerId, String modelId, String forthcomingUserMessage) {
    if (!isEnabled() || sessionId == null || !StringUtils.hasText(providerId) || !StringUtils.hasText(modelId)) {
      return Optional.empty();
    }

    List<Message> history =
        chatMemoryRepository.findByConversationId(sessionId.toString());
    if (history == null) {
      history = List.of();
    }
    List<Message> evaluatedMessages = new java.util.ArrayList<>(history);
    if (StringUtils.hasText(forthcomingUserMessage)) {
      evaluatedMessages.add(UserMessage.builder().text(forthcomingUserMessage.trim()).build());
    }

    String tokenizerOverride = null;
    try {
      var modelConfig = chatProviderService.model(providerId, modelId);
      if (modelConfig != null && modelConfig.getUsage() != null) {
        tokenizerOverride = modelConfig.getUsage().getFallbackTokenizer();
      }
    } catch (RuntimeException ex) {
      log.debug(
          "Failed to resolve tokenizer for summarisation preflight (provider={}, model={}): {}",
          providerId,
          modelId,
          ex.getMessage());
    }

    SummarizationInput input =
        new SummarizationInput(providerId, modelId, evaluatedMessages, tokenizerOverride);
    Optional<SummarizationDecision> decision = evaluate(input);
    return decision.map(result -> new PreflightResult(sessionId, providerId, modelId, evaluatedMessages, result));
  }

  private void recordTokensSaved(PreflightResult result) {
    if (tokensSavedCounter == null || result == null) {
      return;
    }
    long estimate = Math.max(0, result.decision().estimatedTokens());
    long target = Math.max(1, result.decision().targetTokenCount());
    long saved = Math.max(0, estimate - target);
    if (saved > 0) {
      tokensSavedCounter.increment(saved);
    }
  }

  private boolean enqueueSummarization(PreflightResult result) {
    try {
      summarizationExecutor.execute(() -> runSummarizationTask(result));
      return true;
    } catch (RejectedExecutionException rejectedExecutionException) {
      if (queueRejectedCounter != null) {
        queueRejectedCounter.increment();
      }
      return false;
    }
  }

  private void runSummarizationTask(PreflightResult result) {
    acquireSlot();
    long start = System.nanoTime();
    try {
      summarizeConversation(result);
      recordTokensSaved(result);
      recordSummaryRun(System.nanoTime() - start);
    } catch (Exception exception) {
      recordFailure(result.sessionId(), "chat", exception);
    } finally {
      releaseSlot();
    }
  }

  private void acquireSlot() {
    concurrencyLimiter.acquireUninterruptibly();
  }

  private void recordFailure(UUID sessionId, String scope, Exception exception) {
    String reason = exception != null ? exception.getMessage() : "Summarisation failed";
    recordFailure(sessionId, scope, reason, exception);
  }

  private void recordFailure(UUID sessionId, String scope, String reason) {
    recordFailure(sessionId, scope, reason, null);
  }

  private void recordFailure(UUID sessionId, String scope, String reason, Exception exception) {
    if (summaryFailureCounter != null) {
      summaryFailureCounter.increment();
    }
    if (sessionId == null) {
      if (exception != null) {
        log.warn("Summarisation failed for {} scope (session unknown): {}", scope, reason, exception);
      } else {
        log.warn("Summarisation failed for {} scope (session unknown): {}", scope, reason);
      }
      return;
    }
    int current =
        failureCounts.computeIfAbsent(sessionId, key -> new AtomicInteger()).incrementAndGet();
    if (current >= FAILURE_ALERT_THRESHOLD) {
      if (summaryFailureAlertCounter != null) {
        summaryFailureAlertCounter.increment();
      }
      log.error(
          "Summarisation repeatedly failed for {} session {} ({} consecutive errors): {}",
          scope,
          sessionId,
          current,
          reason,
          exception);
    } else {
      if (exception != null) {
        log.warn(
            "Summarisation failed for {} session {} ({} consecutive errors, alert at {}): {}",
            scope,
            sessionId,
            current,
            FAILURE_ALERT_THRESHOLD,
            reason,
            exception);
      } else {
        log.warn(
            "Summarisation failed for {} session {} ({} consecutive errors, alert at {}): {}",
            scope,
            sessionId,
            current,
            FAILURE_ALERT_THRESHOLD,
            reason);
      }
    }
  }

  private void resetFailures(UUID sessionId) {
    if (sessionId != null) {
      failureCounts.remove(sessionId);
    }
  }

  public record SummarizationInput(
      String targetProviderId, String targetModelId, List<Message> messages, String tokenizerOverride) {

    public SummarizationInput {
      targetProviderId = StringUtils.hasText(targetProviderId) ? targetProviderId : null;
      targetModelId = StringUtils.hasText(targetModelId) ? targetModelId : null;
    }
  }

  public record SummarizationDecision(
      boolean required, int estimatedTokens, int triggerTokenLimit, int targetTokenCount) {

    public SummarizationDecision {
      estimatedTokens = Math.max(estimatedTokens, 0);
      triggerTokenLimit = Math.max(triggerTokenLimit, 1);
      targetTokenCount = Math.max(targetTokenCount, 1);
    }

    public boolean shouldSummarize() {
      return required;
    }
  }

  public record ChatOptionsEnvelope(ChatProviderSelection selection, ChatOptions options) {}

  public record SummarizerModelInfo(String providerId, String modelId) {}

  public record PreflightResult(
      UUID sessionId,
      String providerId,
      String modelId,
      List<Message> messages,
      SummarizationDecision decision) {}

  private ObjectNode buildSummaryMetadata(int startOrder, int endOrder) {
    ObjectNode node = objectMapper.createObjectNode();
    node.put("schemaVersion", SUMMARY_METADATA_SCHEMA_VERSION);
    node.put("summary", true);
    node.put("sourceStartOrder", startOrder);
    node.put("sourceEndOrder", endOrder);
    return node;
  }

  private ObjectNode buildSessionMetadata(int endOrder) {
    ObjectNode node = objectMapper.createObjectNode();
    node.put("schemaVersion", SUMMARY_METADATA_SCHEMA_VERSION);
    node.put("summaryUntilOrder", endOrder);
    node.put("updatedAt", Instant.now().toString());
    if (StringUtils.hasText(summarizerProviderId) || StringUtils.hasText(summarizerModelId)) {
      ObjectNode modelNode = node.putObject("model");
      if (StringUtils.hasText(summarizerProviderId)) {
        modelNode.put("providerId", summarizerProviderId);
      }
      if (StringUtils.hasText(summarizerModelId)) {
        modelNode.put("modelId", summarizerModelId);
      }
    }
    return node;
  }

  private static final class SummarizationException extends RuntimeException {
    private SummarizationException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  int resolveTailCount(int totalMessages) {
    if (totalMessages <= 1) {
      return 0;
    }
    int configuredTail = Math.max(4, properties.getWindowSize());
    int maxTail = Math.min(configuredTail, totalMessages - 1);
    return Math.max(1, maxTail);
  }

  private int computeSummaryCount(int totalMessages) {
    if (totalMessages < 2) {
      return 0;
    }
    return Math.max(1, totalMessages - resolveTailCount(totalMessages));
  }

  private boolean isSummaryMessage(Message message) {
    if (message == null || message.getMetadata() == null) {
      return false;
    }
    Object flag = message.getMetadata().get("summary");
    if (flag instanceof Boolean booleanValue) {
      return booleanValue;
    }
    return flag != null && Boolean.parseBoolean(flag.toString());
  }
}
