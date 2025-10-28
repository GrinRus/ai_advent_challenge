package com.aiadvent.backend.insight;

import com.aiadvent.backend.chat.domain.ChatMessage;
import com.aiadvent.backend.chat.domain.ChatSession;
import com.aiadvent.backend.chat.domain.ChatMemorySummary;
import com.aiadvent.backend.chat.persistence.ChatMemorySummaryRepository;
import com.aiadvent.backend.chat.persistence.ChatMessageRepository;
import com.aiadvent.backend.chat.persistence.ChatSessionRepository;
import com.aiadvent.backend.flow.domain.FlowMemorySummary;
import com.aiadvent.backend.flow.domain.FlowMemoryVersion;
import com.aiadvent.backend.flow.domain.FlowSession;
import com.aiadvent.backend.flow.memory.FlowMemoryChannels;
import com.aiadvent.backend.flow.persistence.FlowMemorySummaryRepository;
import com.aiadvent.backend.flow.persistence.FlowMemoryVersionRepository;
import com.aiadvent.backend.flow.persistence.FlowSessionRepository;
import com.aiadvent.backend.flow.telemetry.FlowTelemetryService;
import com.aiadvent.backend.insight.api.InsightMemorySearchResponse;
import com.aiadvent.backend.insight.api.InsightMemorySearchResponse.MemoryMatch;
import com.aiadvent.backend.insight.api.InsightMetricsResponse;
import com.aiadvent.backend.insight.api.InsightSessionSummaryResponse;
import com.aiadvent.backend.insight.api.InsightSummaryResponse;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional(readOnly = true)
public class InsightService {

  private static final int DEFAULT_RECENT_LIMIT = 10;
  private static final int MAX_RECENT_LIMIT = 50;
  private static final int DEFAULT_SEARCH_LIMIT = 20;
  private static final int MAX_SEARCH_LIMIT = 100;
  private static final int SEARCH_PAGE_SIZE = 50;
  private static final int MAX_SEARCH_PAGES = 5;
  private static final String DEFAULT_FLOW_CHANNEL = FlowMemoryChannels.CONVERSATION;
  private static final int PREVIEW_LIMIT = 240;

  private final FlowSessionRepository flowSessionRepository;
  private final FlowMemorySummaryRepository flowMemorySummaryRepository;
  private final FlowMemoryVersionRepository flowMemoryVersionRepository;
  private final ChatSessionRepository chatSessionRepository;
  private final ChatMessageRepository chatMessageRepository;
  private final ChatMemorySummaryRepository chatMemorySummaryRepository;
  private final FlowTelemetryService flowTelemetryService;

  public InsightService(
      FlowSessionRepository flowSessionRepository,
      FlowMemorySummaryRepository flowMemorySummaryRepository,
      FlowMemoryVersionRepository flowMemoryVersionRepository,
      ChatSessionRepository chatSessionRepository,
      ChatMessageRepository chatMessageRepository,
      ChatMemorySummaryRepository chatMemorySummaryRepository,
      FlowTelemetryService flowTelemetryService) {
    this.flowSessionRepository = flowSessionRepository;
    this.flowMemorySummaryRepository = flowMemorySummaryRepository;
    this.flowMemoryVersionRepository = flowMemoryVersionRepository;
    this.chatSessionRepository = chatSessionRepository;
    this.chatMessageRepository = chatMessageRepository;
    this.chatMemorySummaryRepository = chatMemorySummaryRepository;
    this.flowTelemetryService = flowTelemetryService;
  }

  public List<InsightSessionSummaryResponse> recentSessions(
      Set<InsightSessionType> types, int limit) {
    int effectiveLimit = normalizeLimit(limit, DEFAULT_RECENT_LIMIT, MAX_RECENT_LIMIT);
    Set<InsightSessionType> requestedTypes =
        (types == null || types.isEmpty())
            ? EnumSet.allOf(InsightSessionType.class)
            : EnumSet.copyOf(types);

    List<InsightSessionSummaryResponse> summaries = new ArrayList<>();
    if (requestedTypes.contains(InsightSessionType.FLOW)) {
      summaries.addAll(loadRecentFlows(effectiveLimit));
    }
    if (requestedTypes.contains(InsightSessionType.CHAT)) {
      summaries.addAll(loadRecentChats(effectiveLimit));
    }

    summaries.sort(
        Comparator.comparing(
                InsightSessionSummaryResponse::lastActivityAt,
                Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(InsightSessionSummaryResponse::createdAt, Comparator.nullsLast(Comparator.reverseOrder())));

    if (summaries.size() > effectiveLimit) {
      return summaries.subList(0, effectiveLimit);
    }
    return summaries;
  }

  public InsightSummaryResponse fetchSummary(
      InsightSessionType type, UUID sessionId, String channel) {
    return switch (type) {
      case FLOW -> fetchFlowSummary(sessionId, channel);
      case CHAT -> fetchChatSummary(sessionId);
    };
  }

  public InsightMemorySearchResponse searchMemory(
      InsightSessionType type, UUID sessionId, String query, String channel, int limit) {
    if (!StringUtils.hasText(query)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Query string must not be empty");
    }
    int effectiveLimit = normalizeLimit(limit, DEFAULT_SEARCH_LIMIT, MAX_SEARCH_LIMIT);

    return switch (type) {
      case FLOW -> searchFlowMemory(sessionId, query, channel, effectiveLimit);
      case CHAT -> searchChatMemory(sessionId, query, effectiveLimit);
    };
  }

  public InsightMetricsResponse fetchMetrics(InsightSessionType type, UUID sessionId) {
    if (type != InsightSessionType.FLOW) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Metrics are only available for flow sessions");
    }
    FlowSession session =
        flowSessionRepository
            .findById(sessionId)
            .orElseThrow(
                () ->
                    new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Flow session not found: " + sessionId));

    FlowTelemetryService.FlowTelemetrySnapshot snapshot =
        flowTelemetryService
            .snapshot(sessionId)
            .orElseGet(
                () ->
                    new FlowTelemetryService.FlowTelemetrySnapshot(
                        0,
                        0,
                        0,
                        0.0,
                        0L,
                        0L,
                        session.getStartedAt(),
                        session.getUpdatedAt(),
                        session.getCompletedAt(),
                        session.getStatus()));

    return new InsightMetricsResponse(
        InsightSessionType.FLOW,
        sessionId,
        snapshot.status() != null ? snapshot.status().name() : null,
        snapshot.startedAt(),
        snapshot.completedAt(),
        snapshot.lastUpdated(),
        snapshot.stepsCompleted(),
        snapshot.stepsFailed(),
        snapshot.retriesScheduled(),
        snapshot.totalCostUsd(),
        snapshot.promptTokens(),
        snapshot.completionTokens());
  }

  private List<InsightSessionSummaryResponse> loadRecentFlows(int limit) {
    PageRequest pageRequest =
        PageRequest.of(0, limit, Sort.by(Sort.Direction.DESC, "updatedAt"));
    return flowSessionRepository.findAll(pageRequest).stream()
        .map(this::toFlowSummary)
        .collect(Collectors.toList());
  }

  private List<InsightSessionSummaryResponse> loadRecentChats(int limit) {
    PageRequest pageRequest =
        PageRequest.of(0, limit, Sort.by(Sort.Direction.DESC, "createdAt"));
    return chatSessionRepository.findAll(pageRequest).stream()
        .map(this::toChatSummary)
        .collect(Collectors.toList());
  }

  private InsightSessionSummaryResponse toFlowSummary(FlowSession session) {
    String definitionName =
        session.getFlowDefinition() != null ? session.getFlowDefinition().getName() : null;
    String title =
        definitionName != null
            ? definitionName + " · v" + session.getFlowDefinitionVersion()
            : "Flow " + shortId(session.getId());
    Instant lastActivity = session.getUpdatedAt() != null ? session.getUpdatedAt() : session.getCreatedAt();
    String preview =
        flowMemorySummaryRepository
            .findFirstByFlowSessionAndChannelOrderBySourceVersionEndDesc(
                session, DEFAULT_FLOW_CHANNEL)
            .map(FlowMemorySummary::getSummaryText)
            .map(this::truncate)
            .orElse(null);
    Long stepCount =
        flowMemoryVersionRepository
            .findFirstByFlowSessionAndChannelOrderByVersionDesc(session, DEFAULT_FLOW_CHANNEL)
            .map(FlowMemoryVersion::getVersion)
            .orElse(null);

    return new InsightSessionSummaryResponse(
        InsightSessionType.FLOW,
        session.getId(),
        title,
        session.getCreatedAt(),
        lastActivity,
        session.getStatus() != null ? session.getStatus().name() : null,
        session.getFlowDefinition() != null ? session.getFlowDefinition().getId() : null,
        session.getFlowDefinitionVersion(),
        session.getChatSessionId(),
        null,
        stepCount,
        preview);
  }

  private InsightSessionSummaryResponse toChatSummary(ChatSession session) {
    Optional<ChatMessage> lastMessage =
        chatMessageRepository.findTopBySessionOrderBySequenceNumberDesc(session);
    Instant lastActivity =
        lastMessage.map(ChatMessage::getCreatedAt).orElse(session.getCreatedAt());
    long messageCount = chatMessageRepository.countBySession(session);
    String preview =
        chatMemorySummaryRepository.findBySessionIdOrderBySourceStartOrder(session.getId()).stream()
            .reduce((first, second) -> second)
            .map(ChatMemorySummary::getSummaryText)
            .map(this::truncate)
            .orElse(null);
    String title = "Chat " + shortId(session.getId());

    return new InsightSessionSummaryResponse(
        InsightSessionType.CHAT,
        session.getId(),
        title,
        session.getCreatedAt(),
        lastActivity,
        null,
        null,
        null,
        session.getId(),
        messageCount,
        null,
        preview);
  }

  private InsightSummaryResponse fetchFlowSummary(UUID sessionId, String channel) {
    FlowSession session =
        flowSessionRepository
            .findById(sessionId)
            .orElseThrow(
                () ->
                    new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Flow session not found: " + sessionId));
    boolean allChannels =
        channel != null && channel.trim().equalsIgnoreCase("all");
    String effectiveChannel =
        StringUtils.hasText(channel) && !allChannels ? channel.trim() : DEFAULT_FLOW_CHANNEL;

    List<FlowMemorySummary> summaries =
        allChannels
            ? flowMemorySummaryRepository.findByFlowSessionOrderByCreatedAtDesc(session)
            : flowMemorySummaryRepository.findByFlowSessionAndChannelOrderBySourceVersionStart(
                session, effectiveChannel);

    List<InsightSummaryResponse.Entry> entries =
        summaries.stream()
            .map(
                summary ->
                    new InsightSummaryResponse.Entry(
                        summary.getChannel(),
                        summary.getSourceVersionStart(),
                        summary.getSourceVersionEnd(),
                        summary.getSummaryText(),
                        summary.getTokenCount(),
                        summary.getLanguage(),
                        summary.getMetadata(),
                        summary.getCreatedAt()))
            .toList();

    return new InsightSummaryResponse(InsightSessionType.FLOW, sessionId, entries);
  }

  private InsightSummaryResponse fetchChatSummary(UUID sessionId) {
    chatSessionRepository
        .findById(sessionId)
        .orElseThrow(
            () ->
                new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "Chat session not found: " + sessionId));

    List<InsightSummaryResponse.Entry> entries =
        chatMemorySummaryRepository.findBySessionIdOrderBySourceStartOrder(sessionId).stream()
            .map(
                summary ->
                    new InsightSummaryResponse.Entry(
                        "conversation",
                        (long) summary.getSourceStartOrder(),
                        (long) summary.getSourceEndOrder(),
                        summary.getSummaryText(),
                        summary.getTokenCount(),
                        summary.getLanguage(),
                        summary.getMetadata(),
                        summary.getCreatedAt()))
            .toList();

    return new InsightSummaryResponse(InsightSessionType.CHAT, sessionId, entries);
  }

  private InsightMemorySearchResponse searchFlowMemory(
      UUID sessionId, String query, String channel, int limit) {
    FlowSession session =
        flowSessionRepository
            .findById(sessionId)
            .orElseThrow(
                () ->
                    new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Flow session not found: " + sessionId));
    String effectiveChannel =
        StringUtils.hasText(channel) ? channel.trim() : DEFAULT_FLOW_CHANNEL;

    List<MemoryMatch> matches = new ArrayList<>();

    List<FlowMemorySummary> summaries =
        flowMemorySummaryRepository.findByFlowSessionAndChannelOrderBySourceVersionStart(
            session, effectiveChannel);
    for (FlowMemorySummary summary : summaries) {
      if (containsIgnoreCase(summary.getSummaryText(), query)) {
        matches.add(
            new MemoryMatch(
                "FLOW_SUMMARY",
                summary.getChannel(),
                truncate(summary.getSummaryText()),
                summary.getCreatedAt(),
                null,
                null,
                summary.getStepId(),
                summary.getAttemptEnd(),
                summary.getSourceVersionStart(),
                summary.getSourceVersionEnd(),
                null,
                summary.getMetadata()));
        if (matches.size() >= limit) {
          return new InsightMemorySearchResponse(InsightSessionType.FLOW, sessionId, query, matches);
        }
      }
    }

    int page = 0;
    while (matches.size() < limit && page < MAX_SEARCH_PAGES) {
      Pageable pageable =
          PageRequest.of(page, SEARCH_PAGE_SIZE, Sort.by(Sort.Direction.DESC, "version"));
      Page<FlowMemoryVersion> pageData =
          flowMemoryVersionRepository.findByFlowSessionAndChannel(
              session, effectiveChannel, pageable);
      if (pageData.isEmpty()) {
        break;
      }
      for (FlowMemoryVersion version : pageData) {
        if (jsonContains(version.getData(), query)) {
          matches.add(
              new MemoryMatch(
                  "FLOW_MEMORY",
                  version.getChannel(),
                  preview(version.getData()),
                  version.getCreatedAt(),
                  null,
                  version.getVersion(),
                  version.getStepId(),
                  version.getStepAttempt(),
                  null,
                  null,
                  null,
                  version.getData()));
        }
        if (matches.size() >= limit) {
          break;
        }
      }
      if (!pageData.hasNext()) {
        break;
      }
      page++;
    }

    return new InsightMemorySearchResponse(InsightSessionType.FLOW, sessionId, query, matches);
  }

  private InsightMemorySearchResponse searchChatMemory(
      UUID sessionId, String query, int limit) {
    ChatSession session =
        chatSessionRepository
            .findById(sessionId)
            .orElseThrow(
                () ->
                    new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Chat session not found: " + sessionId));

    List<MemoryMatch> matches = new ArrayList<>();

    List<ChatMemorySummary> summaries =
        chatMemorySummaryRepository.findBySessionIdOrderBySourceStartOrder(sessionId);
    for (ChatMemorySummary summary : summaries) {
      if (containsIgnoreCase(summary.getSummaryText(), query)) {
        matches.add(
            new MemoryMatch(
                "CHAT_SUMMARY",
                "conversation",
                truncate(summary.getSummaryText()),
                summary.getCreatedAt(),
                null,
                null,
                null,
                null,
                (long) summary.getSourceStartOrder(),
                (long) summary.getSourceEndOrder(),
                null,
                summary.getMetadata()));
        if (matches.size() >= limit) {
          return new InsightMemorySearchResponse(InsightSessionType.CHAT, sessionId, query, matches);
        }
      }
    }

    int page = 0;
    while (matches.size() < limit && page < MAX_SEARCH_PAGES) {
      Pageable pageable =
          PageRequest.of(page, SEARCH_PAGE_SIZE, Sort.by(Sort.Direction.DESC, "sequenceNumber"));
      Page<ChatMessage> pageData = chatMessageRepository.findBySession(session, pageable);
      if (pageData.isEmpty()) {
        break;
      }
      for (ChatMessage message : pageData) {
        boolean contentMatch = containsIgnoreCase(message.getContent(), query);
        JsonNode payloadNode =
            message.getStructuredPayload() != null ? message.getStructuredPayload().asJson() : null;
        boolean payloadMatch = payloadNode != null && jsonContains(payloadNode, query);
        if (contentMatch || payloadMatch) {
          matches.add(
              new MemoryMatch(
                  "CHAT_MESSAGE",
                  "conversation",
                  truncate(message.getContent()),
                  message.getCreatedAt(),
                  message.getSequenceNumber(),
                  null,
                  null,
                  null,
                  null,
                  null,
                  message.getId(),
                  payloadNode));
        }
        if (matches.size() >= limit) {
          break;
        }
      }
      if (!pageData.hasNext()) {
        break;
      }
      page++;
    }

    return new InsightMemorySearchResponse(InsightSessionType.CHAT, sessionId, query, matches);
  }

  private int normalizeLimit(int requested, int defaultValue, int maxValue) {
    if (requested <= 0) {
      return defaultValue;
    }
    return Math.min(requested, maxValue);
  }

  private boolean containsIgnoreCase(String text, String fragment) {
    if (!StringUtils.hasText(text) || !StringUtils.hasText(fragment)) {
      return false;
    }
    return text.toLowerCase().contains(fragment.toLowerCase());
  }

  private boolean jsonContains(JsonNode node, String fragment) {
    if (node == null || node.isNull()) {
      return false;
    }
    if (node.isTextual()) {
      return containsIgnoreCase(node.asText(), fragment);
    }
    return containsIgnoreCase(node.toString(), fragment);
  }

  private String truncate(String value) {
    if (!StringUtils.hasText(value)) {
      return value;
    }
    if (value.length() <= PREVIEW_LIMIT) {
      return value;
    }
    return value.substring(0, PREVIEW_LIMIT).trim() + "…";
  }

  private String preview(JsonNode node) {
    if (node == null || node.isNull()) {
      return null;
    }
    if (node.isTextual()) {
      return truncate(node.asText());
    }
    return truncate(node.toString());
  }

  private String shortId(UUID id) {
    String value = id != null ? id.toString() : "";
    int cut = Math.min(8, value.length());
    return value.substring(0, cut);
  }
}
