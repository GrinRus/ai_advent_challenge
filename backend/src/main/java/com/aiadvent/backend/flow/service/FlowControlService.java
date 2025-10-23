package com.aiadvent.backend.flow.service;

import com.aiadvent.backend.flow.config.FlowDefinitionDocument;
import com.aiadvent.backend.flow.config.FlowDefinitionParser;
import com.aiadvent.backend.flow.config.FlowStepConfig;
import com.aiadvent.backend.flow.domain.AgentVersion;
import com.aiadvent.backend.flow.domain.FlowEvent;
import com.aiadvent.backend.flow.domain.FlowEventType;
import com.aiadvent.backend.flow.domain.FlowSession;
import com.aiadvent.backend.flow.domain.FlowSessionStatus;
import com.aiadvent.backend.flow.domain.FlowStepExecution;
import com.aiadvent.backend.flow.domain.FlowStepStatus;
import com.aiadvent.backend.flow.job.FlowJobPayload;
import com.aiadvent.backend.flow.job.JobQueuePort;
import com.aiadvent.backend.flow.persistence.AgentVersionRepository;
import com.aiadvent.backend.flow.persistence.FlowEventRepository;
import com.aiadvent.backend.flow.persistence.FlowSessionRepository;
import com.aiadvent.backend.flow.persistence.FlowStepExecutionRepository;
import com.aiadvent.backend.flow.telemetry.FlowTelemetryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FlowControlService {

  private final FlowSessionRepository flowSessionRepository;
  private final FlowStepExecutionRepository flowStepExecutionRepository;
  private final FlowEventRepository flowEventRepository;
  private final FlowDefinitionParser flowDefinitionParser;
  private final AgentVersionRepository agentVersionRepository;
  private final JobQueuePort jobQueuePort;
  private final ObjectMapper objectMapper;
  private final FlowTelemetryService telemetry;

  public FlowControlService(
      FlowSessionRepository flowSessionRepository,
      FlowStepExecutionRepository flowStepExecutionRepository,
      FlowEventRepository flowEventRepository,
      FlowDefinitionParser flowDefinitionParser,
      AgentVersionRepository agentVersionRepository,
      JobQueuePort jobQueuePort,
      ObjectMapper objectMapper,
      FlowTelemetryService telemetry) {
    this.flowSessionRepository = flowSessionRepository;
    this.flowStepExecutionRepository = flowStepExecutionRepository;
    this.flowEventRepository = flowEventRepository;
    this.flowDefinitionParser = flowDefinitionParser;
    this.agentVersionRepository = agentVersionRepository;
    this.jobQueuePort = jobQueuePort;
    this.objectMapper = objectMapper;
    this.telemetry = telemetry;
  }

  @Transactional
  public FlowSession pause(UUID sessionId, String reason) {
    FlowSession session = getSession(sessionId);
    if (session.getStatus() == FlowSessionStatus.PAUSED) {
      return session;
    }
    session.setStatus(FlowSessionStatus.PAUSED);
    recordEvent(session, FlowEventType.FLOW_PAUSED, "paused", reason);
    return session;
  }

  @Transactional
  public FlowSession resume(UUID sessionId) {
    FlowSession session = getSession(sessionId);
    session.setStatus(FlowSessionStatus.RUNNING);
    recordEvent(session, FlowEventType.FLOW_RESUMED, "running", null);
    return session;
  }

  @Transactional
  public FlowSession cancel(UUID sessionId, String reason) {
    FlowSession session = getSession(sessionId);
    session.setStatus(FlowSessionStatus.CANCELLED);
    session.setCompletedAt(Instant.now());
    recordEvent(session, FlowEventType.FLOW_CANCELLED, "cancelled", reason);
    telemetry.sessionCompleted(
        sessionId,
        session.getStatus(),
        calculateDuration(session.getStartedAt(), session.getCompletedAt()));
    return session;
  }

  @Transactional
  public void retryStep(UUID sessionId, UUID stepExecutionId) {
    FlowSession session = getSession(sessionId);
    FlowStepExecution failedExecution =
        flowStepExecutionRepository
            .findById(stepExecutionId)
            .orElseThrow(() -> new IllegalArgumentException("Step execution not found: " + stepExecutionId));

    if (!failedExecution.getFlowSession().getId().equals(sessionId)) {
      throw new IllegalArgumentException("Step execution does not belong to session " + sessionId);
    }

    FlowDefinitionDocument document = flowDefinitionParser.parse(session.getFlowDefinition());
    FlowStepConfig stepConfig = document.step(failedExecution.getStepId());
    AgentVersion agentVersion =
        agentVersionRepository
            .findById(stepConfig.agentVersionId())
            .orElseThrow(() -> new IllegalStateException("Agent version not found: " + stepConfig.agentVersionId()));

    int nextAttempt = failedExecution.getAttempt() + 1;
    FlowStepExecution retryExecution =
        new FlowStepExecution(session, stepConfig.id(), FlowStepStatus.PENDING, nextAttempt);
    retryExecution.setAgentVersion(agentVersion);
    retryExecution.setStepName(stepConfig.name());
    retryExecution.setInputPayload(failedExecution.getInputPayload());
    flowStepExecutionRepository.save(retryExecution);

    FlowJobPayload payload =
        new FlowJobPayload(session.getId(), retryExecution.getId(), retryExecution.getStepId(), nextAttempt);
    jobQueuePort.enqueueStepJob(session, retryExecution, payload, Instant.now());

    FlowEvent retryEvent =
        new FlowEvent(session, FlowEventType.STEP_RETRY_SCHEDULED, "scheduled", null);
    retryEvent.setFlowStepExecution(retryExecution);
    flowEventRepository.save(retryEvent);
    session.setStatus(FlowSessionStatus.RUNNING);
    telemetry.retryScheduled(session.getId(), retryExecution.getStepId(), nextAttempt);
  }

  private FlowSession getSession(UUID sessionId) {
    return flowSessionRepository
        .findById(sessionId)
        .orElseThrow(() -> new IllegalArgumentException("Flow session not found: " + sessionId));
  }

  private void recordEvent(FlowSession session, FlowEventType eventType, String status, String message) {
    ObjectNode payload = null;
    if (message != null) {
      payload = objectMapper.createObjectNode().put("message", message);
    }
    FlowEvent event = new FlowEvent(session, eventType, status, payload);
    flowEventRepository.save(event);
    telemetry.sessionEvent(session.getId(), eventType.name().toLowerCase(), message);
  }

  private Duration calculateDuration(Instant start, Instant end) {
    if (start == null || end == null) {
      return null;
    }
    return Duration.between(start, end);
  }
}
