package com.aiadvent.backend.flow.service;

import com.aiadvent.backend.flow.config.FlowDefinitionDocument;
import com.aiadvent.backend.flow.config.FlowDefinitionParser;
import com.aiadvent.backend.flow.config.FlowStepConfig;
import com.aiadvent.backend.flow.domain.AgentVersion;
import com.aiadvent.backend.flow.domain.FlowEvent;
import com.aiadvent.backend.flow.domain.FlowEventType;
import com.aiadvent.backend.flow.domain.FlowInteractionResponseSource;
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
  private final FlowInteractionService flowInteractionService;

  public FlowControlService(
      FlowSessionRepository flowSessionRepository,
      FlowStepExecutionRepository flowStepExecutionRepository,
      FlowEventRepository flowEventRepository,
      FlowDefinitionParser flowDefinitionParser,
      AgentVersionRepository agentVersionRepository,
      JobQueuePort jobQueuePort,
      ObjectMapper objectMapper,
      FlowTelemetryService telemetry,
      FlowInteractionService flowInteractionService) {
    this.flowSessionRepository = flowSessionRepository;
    this.flowStepExecutionRepository = flowStepExecutionRepository;
    this.flowEventRepository = flowEventRepository;
    this.flowDefinitionParser = flowDefinitionParser;
    this.agentVersionRepository = agentVersionRepository;
    this.jobQueuePort = jobQueuePort;
    this.objectMapper = objectMapper;
    this.telemetry = telemetry;
    this.flowInteractionService = flowInteractionService;
  }

  @Transactional
  public FlowSession pause(UUID sessionId, String reason) {
    FlowSession session = getSession(sessionId);
    if (session.getStatus() == FlowSessionStatus.PAUSED) {
      return session;
    }
    session.setStatus(FlowSessionStatus.PAUSED);
    flowSessionRepository.save(session);
    recordEvent(session, FlowEventType.FLOW_PAUSED, "paused", reason);
    return session;
  }

  @Transactional
  public FlowSession resume(UUID sessionId) {
    FlowSession session = getSession(sessionId);
    if (session.getStatus() == FlowSessionStatus.WAITING_USER_INPUT) {
      return session;
    }
    session.setStatus(FlowSessionStatus.RUNNING);
    flowSessionRepository.save(session);
    recordEvent(session, FlowEventType.FLOW_RESUMED, "running", null);
    return session;
  }

  @Transactional
  public FlowSession cancel(UUID sessionId, String reason) {
    FlowSession session = getSession(sessionId);
    session.setStatus(FlowSessionStatus.CANCELLED);
    session.setCompletedAt(Instant.now());
    flowSessionRepository.save(session);
    recordEvent(session, FlowEventType.FLOW_CANCELLED, "cancelled", reason);
    flowInteractionService.autoResolvePendingRequests(
        session, FlowInteractionResponseSource.SYSTEM, null, false);
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
    retryEvent.setTraceId(session.getId().toString());
    retryEvent.setSpanId(retryExecution.getId().toString());
    flowEventRepository.save(retryEvent);
    session.setStatus(FlowSessionStatus.RUNNING);
    telemetry.retryScheduled(session.getId(), retryExecution.getStepId(), nextAttempt);
  }

  @Transactional
  public FlowSession approveStep(UUID sessionId, UUID stepExecutionId) {
    FlowSession session = getSession(sessionId);
    FlowStepExecution execution =
        flowStepExecutionRepository
            .findById(stepExecutionId)
            .orElseThrow(() -> new IllegalArgumentException("Step execution not found: " + stepExecutionId));

    if (execution.getStatus() != FlowStepStatus.WAITING_APPROVAL) {
      throw new IllegalStateException("Step execution " + stepExecutionId + " is not waiting for approval");
    }

    FlowDefinitionDocument document = flowDefinitionParser.parse(session.getFlowDefinition());
    FlowStepConfig config = document.step(execution.getStepId());
    AgentVersion agentVersion =
        agentVersionRepository
            .findById(config.agentVersionId())
            .orElseThrow(() -> new IllegalStateException("Agent version not found: " + config.agentVersionId()));

    int nextAttempt = execution.getAttempt() + 1;
    FlowStepExecution retryExecution =
        new FlowStepExecution(session, config.id(), FlowStepStatus.PENDING, nextAttempt);
    retryExecution.setAgentVersion(agentVersion);
    retryExecution.setStepName(config.name());
    retryExecution.setInputPayload(execution.getInputPayload());
    flowStepExecutionRepository.save(retryExecution);

    execution.setStatus(FlowStepStatus.FAILED);
    flowStepExecutionRepository.save(execution);

    FlowJobPayload payload =
        new FlowJobPayload(session.getId(), retryExecution.getId(), retryExecution.getStepId(), nextAttempt);
    jobQueuePort.enqueueStepJob(session, retryExecution, payload, Instant.now());

    session.setStatus(FlowSessionStatus.RUNNING);
    flowSessionRepository.save(session);

    FlowEvent retryEvent =
        new FlowEvent(session, FlowEventType.STEP_RETRY_SCHEDULED, "approved", null);
    retryEvent.setFlowStepExecution(retryExecution);
    retryEvent.setTraceId(session.getId().toString());
    retryEvent.setSpanId(retryExecution.getId().toString());
    flowEventRepository.save(retryEvent);

    telemetry.retryScheduled(session.getId(), retryExecution.getStepId(), nextAttempt);
    telemetry.sessionEvent(session.getId(), "step_approved", execution.getStepId());
    return session;
  }

  @Transactional
  public FlowSession skipStep(UUID sessionId, UUID stepExecutionId) {
    FlowSession session = getSession(sessionId);
    FlowStepExecution execution =
        flowStepExecutionRepository
            .findById(stepExecutionId)
            .orElseThrow(() -> new IllegalArgumentException("Step execution not found: " + stepExecutionId));

    if (execution.getStatus() != FlowStepStatus.WAITING_APPROVAL) {
      throw new IllegalStateException("Step execution " + stepExecutionId + " is not waiting for approval");
    }

    FlowDefinitionDocument document = flowDefinitionParser.parse(session.getFlowDefinition());
    FlowStepConfig config = document.step(execution.getStepId());

    execution.setStatus(FlowStepStatus.SKIPPED);
    execution.setCompletedAt(Instant.now());
    flowStepExecutionRepository.save(execution);

    FlowEvent skippedEvent =
        new FlowEvent(session, FlowEventType.STEP_SKIPPED, "skipped", null);
    skippedEvent.setFlowStepExecution(execution);
    skippedEvent.setTraceId(session.getId().toString());
    skippedEvent.setSpanId(execution.getId().toString());
    flowEventRepository.save(skippedEvent);

    String nextStepId = config.transitions().onFailure();
    boolean failFlow = config.transitions().failFlowOnFailure();

    if (nextStepId != null && !nextStepId.isBlank()) {
      FlowStepConfig nextConfig = document.step(nextStepId);
      AgentVersion nextAgent =
          agentVersionRepository
              .findById(nextConfig.agentVersionId())
              .orElseThrow(() -> new IllegalStateException("Agent version not found: " + nextConfig.agentVersionId()));

      FlowStepExecution nextExecution =
          new FlowStepExecution(session, nextConfig.id(), FlowStepStatus.PENDING, 1);
      nextExecution.setAgentVersion(nextAgent);
      nextExecution.setStepName(nextConfig.name());
      nextExecution.setInputPayload(buildStepInputContext(session));
      flowStepExecutionRepository.save(nextExecution);

      FlowJobPayload payload =
          new FlowJobPayload(session.getId(), nextExecution.getId(), nextExecution.getStepId(), 1);
      jobQueuePort.enqueueStepJob(session, nextExecution, payload, Instant.now());

      session.setCurrentStepId(nextConfig.id());
      session.setStatus(FlowSessionStatus.RUNNING);
      flowSessionRepository.save(session);
      telemetry.sessionEvent(session.getId(), "step_skipped", execution.getStepId());
      return session;
    }

    if (failFlow) {
      session.setStatus(FlowSessionStatus.FAILED);
      session.setCompletedAt(Instant.now());
      flowSessionRepository.save(session);
      recordEvent(session, FlowEventType.FLOW_FAILED, "failed", "Step skipped without fallback");
      telemetry.sessionCompleted(
          session.getId(),
          session.getStatus(),
          calculateDuration(session.getStartedAt(), session.getCompletedAt()));
    } else {
      session.setStatus(FlowSessionStatus.RUNNING);
      flowSessionRepository.save(session);
    }

    telemetry.sessionEvent(session.getId(), "step_skipped", execution.getStepId());
    return session;
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
    event.setTraceId(session.getId().toString());
    event.setSpanId(session.getId().toString());
    flowEventRepository.save(event);
    telemetry.sessionEvent(session.getId(), eventType.name().toLowerCase(), message);
  }

  private Duration calculateDuration(Instant start, Instant end) {
    if (start == null || end == null) {
      return null;
    }
    return Duration.between(start, end);
  }

  private com.fasterxml.jackson.databind.JsonNode buildStepInputContext(FlowSession session) {
    ObjectNode input = objectMapper.createObjectNode();
    if (session.getLaunchParameters() != null && !session.getLaunchParameters().isNull()) {
      input.set("launchParameters", session.getLaunchParameters().deepCopy());
    }

    com.fasterxml.jackson.databind.JsonNode shared = session.getSharedContext();
    if (shared != null && !shared.isNull()) {
      input.set("sharedContext", shared.deepCopy());
      com.fasterxml.jackson.databind.JsonNode initial = shared.get("initial");
      if (initial != null) {
        input.set("initialContext", initial.deepCopy());
      }
      com.fasterxml.jackson.databind.JsonNode lastOutput = shared.get("lastOutput");
      if (lastOutput != null && !lastOutput.isNull()) {
        input.set("lastOutput", lastOutput.deepCopy());
      }
      com.fasterxml.jackson.databind.JsonNode current = shared.get("current");
      if (current != null && !current.isNull()) {
        input.set("currentContext", current.deepCopy());
      }
    }
    return input;
  }
}
