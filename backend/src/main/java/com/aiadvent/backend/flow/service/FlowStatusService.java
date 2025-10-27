package com.aiadvent.backend.flow.service;

import com.aiadvent.backend.flow.api.FlowEventDto;
import com.aiadvent.backend.flow.api.FlowStepDetailResponse;
import com.aiadvent.backend.flow.domain.FlowEvent;
import com.aiadvent.backend.flow.domain.FlowSession;
import com.aiadvent.backend.flow.domain.FlowSessionStatus;
import com.aiadvent.backend.flow.persistence.FlowEventRepository;
import com.aiadvent.backend.flow.persistence.FlowSessionRepository;
import com.aiadvent.backend.flow.telemetry.FlowTelemetryService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FlowStatusService {

  private static final Duration DEFAULT_POLL_INTERVAL = Duration.ofMillis(250);

  private final FlowSessionRepository flowSessionRepository;
  private final FlowEventRepository flowEventRepository;
  private final FlowQueryService flowQueryService;
  private final FlowTelemetryService telemetryService;

  public FlowStatusService(
      FlowSessionRepository flowSessionRepository,
      FlowEventRepository flowEventRepository,
      FlowQueryService flowQueryService,
      FlowTelemetryService telemetryService) {
    this.flowSessionRepository = flowSessionRepository;
    this.flowEventRepository = flowEventRepository;
    this.flowQueryService = flowQueryService;
    this.telemetryService = telemetryService;
  }

  @Transactional(readOnly = true)
  public Optional<FlowStatusResponse> pollSession(
      UUID sessionId, Long sinceEventId, Long stateVersion, Duration timeout) {
    Duration effectiveTimeout =
        timeout != null && !timeout.isNegative() ? timeout : Duration.ofSeconds(25);
    Instant deadline = Instant.now().plus(effectiveTimeout);

    long baselineEventId = sinceEventId != null ? sinceEventId : 0L;
    long baselineStateVersion = stateVersion != null ? stateVersion : -1L;

    while (Instant.now().isBefore(deadline)) {
      FlowSession session =
          flowSessionRepository
              .findById(sessionId)
              .orElseThrow(() -> new IllegalArgumentException("Flow session not found: " + sessionId));

      List<FlowEventDto> events =
          flowEventRepository.findByFlowSessionAndIdGreaterThanOrderByIdAsc(session, baselineEventId).stream()
              .map(FlowStatusService::toDto)
              .toList();

      boolean stateChanged = session.getStateVersion() != baselineStateVersion;
      boolean hasEvents = !events.isEmpty();
      boolean terminal = session.getStatus().name().endsWith("ED");

      if (hasEvents || stateChanged || terminal) {
        long nextSince = baselineEventId;
        if (hasEvents) {
          nextSince = events.get(events.size() - 1).eventId();
        } else {
          FlowEvent last = flowEventRepository.findTopByFlowSessionOrderByIdDesc(session);
          if (last != null) {
            nextSince = last.getId();
          }
        }
        return Optional.of(buildResponse(session, events, nextSince));
      }

      sleep(DEFAULT_POLL_INTERVAL);
    }

    return Optional.empty();
  }

  @Transactional(readOnly = true)
  public FlowStatusResponse currentSnapshot(UUID sessionId) {
    FlowSession session =
        flowSessionRepository
            .findById(sessionId)
            .orElseThrow(() -> new IllegalArgumentException("Flow session not found: " + sessionId));

    List<FlowEventDto> events =
        flowEventRepository.findByFlowSessionOrderByIdAsc(session).stream()
            .map(FlowStatusService::toDto)
            .toList();

    long nextSince = events.isEmpty() ? 0L : events.get(events.size() - 1).eventId();
    return buildResponse(session, events, nextSince);
  }

  @Transactional(readOnly = true)
  public FlowStepDetailResponse stepDetails(UUID sessionId, String stepId) {
    return flowQueryService.fetchStepDetails(sessionId, stepId);
  }

  private FlowStatusResponse buildResponse(
      FlowSession session, List<FlowEventDto> events, long nextSinceEventId) {
    var telemetrySnapshot = telemetryService.snapshot(session.getId()).orElse(null);
    FlowStateDto state =
        new FlowStateDto(
            session.getId(),
            session.getStatus(),
            session.getCurrentStepId(),
            session.getStateVersion(),
            session.getCurrentMemoryVersion(),
        session.getStartedAt(),
        session.getCompletedAt(),
        session.getFlowDefinition().getId(),
        session.getFlowDefinitionVersion(),
        session.getSharedContext().isEmpty() ? null : session.getSharedContext().asJson());

    return new FlowStatusResponse(state, events, nextSinceEventId, telemetrySnapshot);
  }

  private static FlowEventDto toDto(FlowEvent event) {
    return new FlowEventDto(
        event.getId(),
        event.getEventType(),
        event.getStatus(),
        event.getTraceId(),
        event.getSpanId(),
        event.getCost(),
        event.getTokensPrompt(),
        event.getTokensCompletion(),
        event.getCreatedAt(),
        event.getPayload().asJson());
  }

  private void sleep(Duration duration) {
    try {
      Thread.sleep(duration.toMillis());
    } catch (InterruptedException ignored) {
      Thread.currentThread().interrupt();
    }
  }

  public record FlowStateDto(
      UUID sessionId,
      FlowSessionStatus status,
      String currentStepId,
      long stateVersion,
      long currentMemoryVersion,
      java.time.Instant startedAt,
      java.time.Instant completedAt,
      UUID flowDefinitionId,
      int flowDefinitionVersion,
      com.fasterxml.jackson.databind.JsonNode sharedContext) {}

  public record FlowStatusResponse(
      FlowStateDto state,
      List<FlowEventDto> events,
      long nextSinceEventId,
      FlowTelemetryService.FlowTelemetrySnapshot telemetry) {}
}
