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
import com.aiadvent.backend.flow.persistence.FlowMemorySummaryRepository;
import com.aiadvent.backend.flow.persistence.FlowMemoryVersionRepository;
import com.aiadvent.backend.flow.persistence.FlowSessionRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@Slf4j
public class FlowMemorySummarizerService {

  private static final String DEFAULT_CHANNEL = "conversation";

  private final FlowSessionRepository flowSessionRepository;
  private final FlowMemoryVersionRepository flowMemoryVersionRepository;
  private final FlowMemorySummaryRepository flowMemorySummaryRepository;
  private final ChatMemorySummarizerService chatMemorySummarizerService;
  private final ChatMemoryProperties chatMemoryProperties;
  private final TokenUsageEstimator tokenUsageEstimator;
  private final ObjectMapper objectMapper;
  private final ChatProviderService chatProviderService;

  public FlowMemorySummarizerService(
      FlowSessionRepository flowSessionRepository,
      FlowMemoryVersionRepository flowMemoryVersionRepository,
      FlowMemorySummaryRepository flowMemorySummaryRepository,
      ChatMemorySummarizerService chatMemorySummarizerService,
      ChatMemoryProperties chatMemoryProperties,
      TokenUsageEstimator tokenUsageEstimator,
      ObjectMapper objectMapper,
      ChatProviderService chatProviderService) {
    this.flowSessionRepository = Objects.requireNonNull(flowSessionRepository, "flowSessionRepository must not be null");
    this.flowMemoryVersionRepository =
        Objects.requireNonNull(flowMemoryVersionRepository, "flowMemoryVersionRepository must not be null");
    this.flowMemorySummaryRepository =
        Objects.requireNonNull(flowMemorySummaryRepository, "flowMemorySummaryRepository must not be null");
    this.chatMemorySummarizerService =
        Objects.requireNonNull(chatMemorySummarizerService, "chatMemorySummarizerService must not be null");
    this.chatMemoryProperties =
        Objects.requireNonNull(chatMemoryProperties, "chatMemoryProperties must not be null");
    this.tokenUsageEstimator = Objects.requireNonNull(tokenUsageEstimator, "tokenUsageEstimator must not be null");
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    this.chatProviderService = Objects.requireNonNull(chatProviderService, "chatProviderService must not be null");
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

    FlowSession session =
        flowSessionRepository
            .findById(flowSessionId)
            .orElse(null);
    if (session == null) {
      return Optional.empty();
    }

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
    if (orderedEntries.size() <= tailSize()) {
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

    if (estimatedTokens <= chatMemorySummarizerService.triggerTokenLimit()) {
      return Optional.empty();
    }

    int summaryCount = transcript.size() - tailSize();
    if (summaryCount <= 0) {
      return Optional.empty();
    }
    List<FlowTranscriptEntry> toSummarize = orderedEntries.subList(0, summaryCount);

    SummarizationDecision decision =
        new SummarizationDecision(true, estimatedTokens, chatMemorySummarizerService.triggerTokenLimit(),
            chatMemorySummarizerService.targetTokenCount());

    return Optional.of(
        new PreflightResult(
            flowSessionId, channel, providerId, modelId, tokenizer, toSummarize, decision));
  }

  @Transactional
  public void processPreflightResult(PreflightResult result) {
    if (result == null || result.entries().isEmpty()) {
      return;
    }
    log.info(
        "Flow summarisation scheduled for session {} channel {} (estimated {} tokens, trigger {}).",
        result.sessionId(),
        result.channel(),
        result.decision().estimatedTokens(),
        result.decision().triggerTokenLimit());
    if (!chatMemorySummarizerService.tryAcquireSlot()) {
      log.info(
          "Flow summarisation skipped for session {} because the summariser worker pool is busy",
          result.sessionId());
      return;
    }
    long started = System.nanoTime();
    try {
      String prompt = buildPrompt(result.entries());
      if (!StringUtils.hasText(prompt)) {
        return;
      }
      Optional<String> summaryText =
          chatMemorySummarizerService.summarizeTranscript(result.sessionId(), prompt, "flow");
      if (summaryText.isEmpty()) {
        log.warn("Summarisation model returned empty response for flow session {}", result.sessionId());
        return;
      }
      persistSummary(result, summaryText.get());
      chatMemorySummarizerService.recordSummaryRun(System.nanoTime() - started);
    } catch (Exception exception) {
      log.warn(
          "Failed to summarise flow session {}: {}",
          result.sessionId(),
          exception.getMessage(),
          exception);
    } finally {
      chatMemorySummarizerService.releaseSlot();
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

    summary.setMetadata(buildMetadata(result.entries()));

    flowMemorySummaryRepository.save(summary);
    log.info(
        "Summarised flow session {} channel {} covering versions {}-{}",
        result.sessionId(),
        result.channel(),
        startVersion,
        endVersion);
  }

  private ObjectNode buildMetadata(List<FlowTranscriptEntry> entries) {
    ObjectNode metadata = objectMapper.createObjectNode();
    metadata.put("entriesSummarized", entries.size());
    entries.stream()
        .map(FlowTranscriptEntry::agentVersionId)
        .filter(Objects::nonNull)
        .reduce((first, second) -> second)
        .ifPresent(id -> metadata.put("agentVersionId", id.toString()));
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

  private int tailSize() {
    return Math.max(4, chatMemoryProperties.getWindowSize());
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
}
