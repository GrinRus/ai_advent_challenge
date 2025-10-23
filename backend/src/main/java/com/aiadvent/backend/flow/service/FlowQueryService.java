package com.aiadvent.backend.flow.service;

import com.aiadvent.backend.flow.api.FlowEventDto;
import com.aiadvent.backend.flow.api.FlowStepDetailResponse;
import com.aiadvent.backend.flow.domain.FlowEvent;
import com.aiadvent.backend.flow.domain.FlowSession;
import com.aiadvent.backend.flow.domain.FlowStepExecution;
import com.aiadvent.backend.flow.persistence.FlowEventRepository;
import com.aiadvent.backend.flow.persistence.FlowSessionRepository;
import com.aiadvent.backend.flow.persistence.FlowStepExecutionRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FlowQueryService {

  private final FlowSessionRepository flowSessionRepository;
  private final FlowStepExecutionRepository flowStepExecutionRepository;
  private final FlowEventRepository flowEventRepository;

  public FlowQueryService(
      FlowSessionRepository flowSessionRepository,
      FlowStepExecutionRepository flowStepExecutionRepository,
      FlowEventRepository flowEventRepository) {
    this.flowSessionRepository = flowSessionRepository;
    this.flowStepExecutionRepository = flowStepExecutionRepository;
    this.flowEventRepository = flowEventRepository;
  }

  @Transactional(readOnly = true)
  public FlowStepDetailResponse fetchStepDetails(UUID sessionId, String stepId) {
    FlowSession session =
        flowSessionRepository
            .findById(sessionId)
            .orElseThrow(() -> new IllegalArgumentException("Flow session not found: " + sessionId));

    FlowStepExecution stepExecution =
        flowStepExecutionRepository
            .findFirstByFlowSessionAndStepIdOrderByAttemptDesc(session, stepId)
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "Step '" + stepId + "' not found for session " + sessionId));

    List<FlowEventDto> events =
        flowEventRepository.findByFlowStepExecutionOrderByIdAsc(stepExecution).stream()
            .map(FlowQueryService::toDto)
            .toList();

    return new FlowStepDetailResponse(
        session.getId(),
        stepExecution.getId(),
        stepExecution.getStepId(),
        stepExecution.getStepName(),
        stepExecution.getStatus(),
        stepExecution.getAttempt(),
        stepExecution.getAgentVersion() != null ? stepExecution.getAgentVersion().getId() : null,
        stepExecution.getPrompt(),
        stepExecution.getInputPayload(),
        stepExecution.getOutputPayload(),
        stepExecution.getUsage(),
        stepExecution.getCost(),
        stepExecution.getStartedAt(),
        stepExecution.getCompletedAt(),
        events);
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
        event.getPayload());
  }
}
