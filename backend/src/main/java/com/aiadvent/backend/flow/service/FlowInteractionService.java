package com.aiadvent.backend.flow.service;

import com.aiadvent.backend.flow.config.FlowInteractionConfig;
import com.aiadvent.backend.flow.domain.AgentVersion;
import com.aiadvent.backend.flow.domain.FlowEvent;
import com.aiadvent.backend.flow.domain.FlowEventType;
import com.aiadvent.backend.flow.domain.FlowInteractionRequest;
import com.aiadvent.backend.flow.domain.FlowInteractionResponse;
import com.aiadvent.backend.flow.domain.FlowInteractionResponseSource;
import com.aiadvent.backend.flow.domain.FlowInteractionStatus;
import com.aiadvent.backend.flow.domain.FlowSession;
import com.aiadvent.backend.flow.domain.FlowSessionStatus;
import com.aiadvent.backend.flow.domain.FlowStepExecution;
import com.aiadvent.backend.flow.domain.FlowStepStatus;
import com.aiadvent.backend.flow.job.FlowJobPayload;
import com.aiadvent.backend.flow.job.JobQueuePort;
import com.aiadvent.backend.flow.persistence.FlowEventRepository;
import com.aiadvent.backend.flow.persistence.FlowInteractionRequestRepository;
import com.aiadvent.backend.flow.persistence.FlowInteractionResponseRepository;
import com.aiadvent.backend.flow.persistence.FlowSessionRepository;
import com.aiadvent.backend.flow.persistence.FlowStepExecutionRepository;
import com.aiadvent.backend.flow.telemetry.FlowTelemetryService;
import com.aiadvent.backend.flow.validation.FlowInteractionSchemaValidator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FlowInteractionService {

  private static final Logger log = LoggerFactory.getLogger(FlowInteractionService.class);

  private final FlowInteractionRequestRepository requestRepository;
  private final FlowInteractionResponseRepository responseRepository;
  private final FlowSessionRepository flowSessionRepository;
  private final FlowStepExecutionRepository flowStepExecutionRepository;
  private final FlowEventRepository flowEventRepository;
  private final JobQueuePort jobQueuePort;
  private final FlowTelemetryService telemetry;
  private final ObjectMapper objectMapper;
  private final FlowInteractionSchemaValidator schemaValidator;

  public FlowInteractionService(
      FlowInteractionRequestRepository requestRepository,
      FlowInteractionResponseRepository responseRepository,
      FlowSessionRepository flowSessionRepository,
      FlowStepExecutionRepository flowStepExecutionRepository,
      FlowEventRepository flowEventRepository,
      JobQueuePort jobQueuePort,
      FlowTelemetryService telemetry,
      ObjectMapper objectMapper,
      FlowInteractionSchemaValidator schemaValidator) {
    this.requestRepository = requestRepository;
    this.responseRepository = responseRepository;
    this.flowSessionRepository = flowSessionRepository;
    this.flowStepExecutionRepository = flowStepExecutionRepository;
    this.flowEventRepository = flowEventRepository;
    this.jobQueuePort = jobQueuePort;
    this.telemetry = telemetry;
    this.objectMapper = objectMapper;
    this.schemaValidator = schemaValidator;
  }

  @Transactional(readOnly = true)
  public java.util.List<FlowInteractionRequest> findRequests(UUID sessionId) {
    return requestRepository.findByFlowSessionIdOrderByCreatedAtDesc(sessionId);
  }

  @Transactional(readOnly = true)
  public java.util.Optional<FlowInteractionResponse> findLatestResponse(
      FlowInteractionRequest request) {
     return responseRepository.findFirstByRequestOrderByCreatedAtDesc(request);
  }

  @Transactional(readOnly = true)
  public FlowInteractionRequest getRequest(UUID sessionId, UUID requestId) {
    FlowInteractionRequest request =
        requestRepository
            .findById(requestId)
            .orElseThrow(() -> new IllegalArgumentException("Interaction request not found: " + requestId));
    if (!request.getFlowSession().getId().equals(sessionId)) {
      throw new IllegalArgumentException(
          "Interaction request " + requestId + " does not belong to session " + sessionId);
    }
    return request;
  }

  @Transactional
  public void autoResolvePendingRequests(
      FlowSession session,
      FlowInteractionResponseSource source,
      UUID respondedBy,
      boolean resumeStep) {
    FlowInteractionResponseSource effectiveSource =
        source != null ? source : FlowInteractionResponseSource.SYSTEM;
    java.util.List<FlowInteractionRequest> pending =
        requestRepository.findByFlowSessionAndStatus(session, FlowInteractionStatus.PENDING);
    for (FlowInteractionRequest request : pending) {
      recordResponse(
          request.getId(),
          request.getChatSessionId(),
          respondedBy,
          null,
          effectiveSource,
          FlowInteractionStatus.AUTO_RESOLVED,
          FlowEventType.HUMAN_INTERACTION_AUTO_RESOLVED,
          resumeStep);
    }
  }

  @Transactional
  public FlowInteractionResponse autoResolve(
      UUID sessionId,
      UUID requestId,
      JsonNode payload,
      FlowInteractionResponseSource source,
      UUID respondedBy) {
    FlowInteractionRequest request =
        requestRepository
            .findById(requestId)
            .orElseThrow(() -> new IllegalArgumentException("Interaction request not found: " + requestId));

    if (!request.getFlowSession().getId().equals(sessionId)) {
      throw new IllegalArgumentException(
          "Interaction request " + requestId + " does not belong to session " + sessionId);
    }

    FlowInteractionResponseSource effectiveSource =
        source != null ? source : FlowInteractionResponseSource.SYSTEM;

    if (payload != null && !payload.isNull()) {
      schemaValidator.validatePayload(request.getPayloadSchema(), payload);
    }

    return recordResponse(
        requestId,
        request.getChatSessionId(),
        respondedBy,
        payload,
        effectiveSource,
        FlowInteractionStatus.AUTO_RESOLVED,
        FlowEventType.HUMAN_INTERACTION_AUTO_RESOLVED,
        true);
  }

  @Transactional
  public FlowInteractionResponse respond(
      UUID sessionId,
      UUID requestId,
      UUID chatSessionId,
      UUID respondedBy,
      JsonNode payload,
      FlowInteractionResponseSource source,
      FlowInteractionStatus finalStatus) {

    FlowInteractionResponseSource effectiveSource =
        source != null ? source : FlowInteractionResponseSource.USER;

    FlowInteractionRequest request =
        requestRepository
            .findById(requestId)
            .orElseThrow(() -> new IllegalArgumentException("Interaction request not found: " + requestId));

    if (!request.getFlowSession().getId().equals(sessionId)) {
      throw new IllegalArgumentException(
          "Interaction request " + requestId + " does not belong to session " + sessionId);
    }

    schemaValidator.validatePayload(request.getPayloadSchema(), payload);

    FlowEventType eventType =
        finalStatus == FlowInteractionStatus.AUTO_RESOLVED
            ? FlowEventType.HUMAN_INTERACTION_AUTO_RESOLVED
            : FlowEventType.HUMAN_INTERACTION_RESPONDED;

    return recordResponse(
        requestId,
        chatSessionId,
        respondedBy,
        payload,
        effectiveSource,
        finalStatus,
        eventType,
        true);
  }

  @Transactional
  public FlowInteractionRequest ensureRequest(
      FlowSession session,
      FlowStepExecution stepExecution,
      FlowInteractionConfig config,
      AgentVersion agentVersion) {

    Optional<FlowInteractionRequest> existing =
        requestRepository.findByFlowStepExecutionId(stepExecution.getId());

    if (existing.isPresent()) {
      FlowInteractionRequest request = existing.get();
      if (request.getStatus() == FlowInteractionStatus.PENDING) {
        return request;
      }
      if (request.getStatus() == FlowInteractionStatus.ANSWERED
          || request.getStatus() == FlowInteractionStatus.AUTO_RESOLVED) {
        return request;
      }
    }

    if (session.getChatSessionId() == null) {
      throw new IllegalStateException(
          "Flow session " + session.getId() + " does not declare chatSessionId");
    }

    FlowInteractionRequest request =
        new FlowInteractionRequest(
            session,
            stepExecution,
            session.getChatSessionId(),
            agentVersion,
            config.type(),
            FlowInteractionStatus.PENDING);

    request.setTitle(config.title());
    request.setDescription(config.description());
    request.setPayloadSchema(cloneNode(config.payloadSchema()));
    request.setSuggestedActions(cloneNode(config.suggestedActions()));
    if (config.dueInMinutes() != null) {
      request.setDueAt(Instant.now().plus(Duration.ofMinutes(config.dueInMinutes())));
    }

    request = requestRepository.save(request);

    stepExecution.setStatus(FlowStepStatus.WAITING_USER_INPUT);
    flowStepExecutionRepository.save(stepExecution);

    session.setStatus(FlowSessionStatus.WAITING_USER_INPUT);
    session.setStateVersion(session.getStateVersion() + 1);
    flowSessionRepository.save(session);

    telemetry.interactionCreated(request.getId());

    FlowEvent event =
        new FlowEvent(session, FlowEventType.HUMAN_INTERACTION_REQUIRED, "waiting", eventPayload(request));
    event.setFlowStepExecution(stepExecution);
    event.setTraceId(session.getId().toString());
    event.setSpanId(stepExecution.getId().toString());
    flowEventRepository.save(event);

    telemetry.sessionEvent(session.getId(), "interaction_required", stepExecution.getStepId());
    log.debug(
        "Created interaction request {} for session {} step {}",
        request.getId(),
        session.getId(),
        stepExecution.getStepId());

    return request;
  }

  @Transactional
  public FlowInteractionResponse recordResponse(
      UUID requestId,
      UUID chatSessionId,
      UUID respondedBy,
      JsonNode payload,
      FlowInteractionResponseSource source,
      FlowInteractionStatus finalStatus,
      FlowEventType eventType,
      boolean resumeStep) {

    if (finalStatus == null
        || finalStatus == FlowInteractionStatus.PENDING
        || finalStatus == FlowInteractionStatus.EXPIRED) {
      throw new IllegalArgumentException("Unsupported final status for response: " + finalStatus);
    }

    if (eventType == null) {
      throw new IllegalArgumentException("Event type must be provided");
    }

    FlowInteractionRequest request =
        requestRepository
            .findById(requestId)
            .orElseThrow(() -> new IllegalArgumentException("Interaction request not found: " + requestId));

    if (request.getStatus() != FlowInteractionStatus.PENDING) {
      throw new IllegalStateException(
          "Interaction request " + requestId + " is not pending (" + request.getStatus() + ")");
    }

    if (!request.getChatSessionId().equals(chatSessionId)) {
      throw new IllegalArgumentException(
          "Chat session mismatch for interaction request " + requestId);
    }

    if (responseRepository.existsByRequest(request)) {
      throw new IllegalStateException("Interaction request " + requestId + " already has a response");
    }

    FlowInteractionResponse response =
        new FlowInteractionResponse(request, chatSessionId, respondedBy, cloneNode(payload), source);
    response = responseRepository.save(response);

    request.setStatus(finalStatus);
    requestRepository.save(request);

    FlowStepExecution stepExecution = request.getFlowStepExecution();
    FlowSession session = request.getFlowSession();

    if (resumeStep) {
      stepExecution.setStatus(FlowStepStatus.PENDING);
      stepExecution.setInputPayload(
          mergeInteractionResponse(stepExecution.getInputPayload(), request, payload, source, finalStatus));
      flowStepExecutionRepository.save(stepExecution);

      session.setStatus(FlowSessionStatus.RUNNING);
      session.setStateVersion(session.getStateVersion() + 1);
      flowSessionRepository.save(session);
    } else {
      stepExecution.setStatus(FlowStepStatus.CANCELLED);
      stepExecution.setCompletedAt(Instant.now());
      flowStepExecutionRepository.save(stepExecution);
      flowSessionRepository.save(session);
    }

    FlowEvent event =
        new FlowEvent(
            session,
            eventType,
            finalStatus.name().toLowerCase(),
            eventPayloadWithResponse(request, response, finalStatus));
    event.setFlowStepExecution(stepExecution);
    event.setTraceId(session.getId().toString());
    event.setSpanId(stepExecution.getId().toString());
    flowEventRepository.save(event);

    telemetry.sessionEvent(session.getId(), "interaction_responded", stepExecution.getStepId());

    if (resumeStep) {
      FlowJobPayload jobPayload =
          new FlowJobPayload(
              session.getId(), stepExecution.getId(), stepExecution.getStepId(), stepExecution.getAttempt());
      jobQueuePort.enqueueStepJob(session, stepExecution, jobPayload, Instant.now());
    }

    telemetry.interactionResolved(request.getId(), finalStatus);

    log.debug(
        "Recorded response {} for interaction {} and re-enqueued step {}",
        response.getId(),
        request.getId(),
        stepExecution.getStepId());

    return response;
  }

  private JsonNode mergeInteractionResponse(
      JsonNode existing,
      FlowInteractionRequest request,
      JsonNode payload,
      FlowInteractionResponseSource source,
      FlowInteractionStatus finalStatus) {
    ObjectNode input =
        existing != null && existing.isObject()
            ? ((ObjectNode) existing).deepCopy()
            : objectMapper.createObjectNode();

    ObjectNode interactionNode = input.with("interaction");
    interactionNode.put("requestId", request.getId().toString());
    interactionNode.put("stepId", request.getFlowStepExecution().getStepId());
    interactionNode.put("type", request.getType().name());
    interactionNode.put("status", finalStatus.name());
    if (source != null) {
      interactionNode.put("source", source.name());
    }
    if (request.getDueAt() != null) {
      interactionNode.put("dueAt", request.getDueAt().toString());
    }
    if (payload != null && !payload.isMissingNode()) {
      interactionNode.set("payload", cloneNode(payload));
    }
    return input;
  }

  private JsonNode eventPayload(FlowInteractionRequest request) {
    ObjectNode node = objectMapper.createObjectNode();
    node.put("requestId", request.getId().toString());
    node.put("flowSessionId", request.getFlowSession().getId().toString());
    node.put("stepExecutionId", request.getFlowStepExecution().getId().toString());
    node.put("stepId", request.getFlowStepExecution().getStepId());
    node.put("chatSessionId", request.getChatSessionId().toString());
    node.put("type", request.getType().name());
    if (request.getTitle() != null) {
      node.put("title", request.getTitle());
    }
    if (request.getDescription() != null) {
      node.put("description", request.getDescription());
    }
    if (request.getPayloadSchema() != null && !request.getPayloadSchema().isMissingNode()) {
      node.set("payloadSchema", cloneNode(request.getPayloadSchema()));
    }
    if (request.getSuggestedActions() != null && !request.getSuggestedActions().isMissingNode()) {
      node.set("suggestedActions", cloneNode(request.getSuggestedActions()));
    }
    if (request.getDueAt() != null) {
      node.put("dueAt", request.getDueAt().toString());
    }
    node.put("status", request.getStatus().name());
    return node;
  }

  private JsonNode eventPayloadWithResponse(
      FlowInteractionRequest request,
      FlowInteractionResponse response,
      FlowInteractionStatus finalStatus) {
    ObjectNode node = (ObjectNode) eventPayload(request);
    node.put("responseId", response.getId().toString());
    node.put("responseSource", response.getSource().name());
    if (response.getPayload() != null && !response.getPayload().isMissingNode()) {
      node.set("responsePayload", cloneNode(response.getPayload()));
    }
    if (response.getRespondedBy() != null) {
      node.put("respondedBy", response.getRespondedBy().toString());
    }
    node.put("status", finalStatus.name());
    return node;
  }

  private JsonNode cloneNode(JsonNode node) {
    if (node == null || node.isNull()) {
      return null;
    }
    return node.deepCopy();
  }
}
