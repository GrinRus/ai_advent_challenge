package com.aiadvent.backend.flow.service;

import com.aiadvent.backend.chat.provider.model.ChatRequestOverrides;
import com.aiadvent.backend.chat.provider.model.UsageCostEstimate;
import com.aiadvent.backend.flow.config.FlowDefinitionDocument;
import com.aiadvent.backend.flow.config.FlowDefinitionParser;
import com.aiadvent.backend.flow.config.FlowStepConfig;
import com.aiadvent.backend.flow.config.FlowStepTransitions;
import com.aiadvent.backend.flow.config.MemoryReadConfig;
import com.aiadvent.backend.flow.config.MemoryWriteConfig;
import com.aiadvent.backend.flow.config.MemoryWriteMode;
import com.aiadvent.backend.flow.config.FlowInteractionConfig;
import com.aiadvent.backend.flow.domain.AgentVersion;
import com.aiadvent.backend.flow.domain.FlowDefinition;
import com.aiadvent.backend.flow.domain.FlowEvent;
import com.aiadvent.backend.flow.domain.FlowEventType;
import com.aiadvent.backend.flow.domain.FlowInteractionRequest;
import com.aiadvent.backend.flow.domain.FlowInteractionStatus;
import com.aiadvent.backend.flow.domain.FlowJob;
import com.aiadvent.backend.flow.domain.FlowJobStatus;
import com.aiadvent.backend.flow.domain.FlowMemoryVersion;
import com.aiadvent.backend.flow.domain.FlowSession;
import com.aiadvent.backend.flow.domain.FlowSessionStatus;
import com.aiadvent.backend.flow.domain.FlowStepExecution;
import com.aiadvent.backend.flow.domain.FlowStepStatus;
import com.aiadvent.backend.flow.job.FlowJobPayload;
import com.aiadvent.backend.flow.job.JobQueuePort;
import com.aiadvent.backend.flow.memory.FlowMemoryMetadata;
import com.aiadvent.backend.flow.memory.FlowMemoryService;
import com.aiadvent.backend.flow.memory.FlowMemorySourceType;
import com.aiadvent.backend.flow.telemetry.FlowTelemetryService;
import com.aiadvent.backend.flow.persistence.AgentVersionRepository;
import com.aiadvent.backend.flow.persistence.FlowEventRepository;
import com.aiadvent.backend.flow.persistence.FlowSessionRepository;
import com.aiadvent.backend.flow.persistence.FlowStepExecutionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class AgentOrchestratorService {

  private static final Logger log = LoggerFactory.getLogger(AgentOrchestratorService.class);

  private final FlowDefinitionService flowDefinitionService;
  private final FlowDefinitionParser flowDefinitionParser;
  private final FlowSessionRepository flowSessionRepository;
  private final FlowStepExecutionRepository flowStepExecutionRepository;
  private final FlowEventRepository flowEventRepository;
  private final AgentVersionRepository agentVersionRepository;
  private final AgentInvocationService agentInvocationService;
  private final FlowMemoryService flowMemoryService;
  private final FlowInteractionService flowInteractionService;
  private final JobQueuePort jobQueuePort;
  private final ObjectMapper objectMapper;
  private final FlowTelemetryService telemetry;

  public AgentOrchestratorService(
      FlowDefinitionService flowDefinitionService,
      FlowDefinitionParser flowDefinitionParser,
      FlowSessionRepository flowSessionRepository,
      FlowStepExecutionRepository flowStepExecutionRepository,
      FlowEventRepository flowEventRepository,
      AgentVersionRepository agentVersionRepository,
      AgentInvocationService agentInvocationService,
      FlowMemoryService flowMemoryService,
      FlowInteractionService flowInteractionService,
      JobQueuePort jobQueuePort,
      ObjectMapper objectMapper,
      FlowTelemetryService telemetry) {
    this.flowDefinitionService = flowDefinitionService;
    this.flowDefinitionParser = flowDefinitionParser;
    this.flowSessionRepository = flowSessionRepository;
    this.flowStepExecutionRepository = flowStepExecutionRepository;
    this.flowEventRepository = flowEventRepository;
    this.agentVersionRepository = agentVersionRepository;
    this.agentInvocationService = agentInvocationService;
    this.flowMemoryService = flowMemoryService;
    this.flowInteractionService = flowInteractionService;
    this.jobQueuePort = jobQueuePort;
    this.objectMapper = objectMapper;
    this.telemetry = telemetry;
  }

  @Transactional
  public FlowSession start(
      UUID flowDefinitionId,
      JsonNode launchParameters,
      JsonNode sharedContext,
      ChatRequestOverrides launchOverrides,
      UUID chatSessionId) {
    FlowDefinition flowDefinition =
        flowDefinitionService.getActivePublishedDefinition(flowDefinitionId);

    FlowDefinitionDocument document = flowDefinitionParser.parse(flowDefinition);
    FlowStepConfig startStep = document.step(document.startStepId());
    AgentVersion agentVersion =
        agentVersionRepository
            .findById(startStep.agentVersionId())
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Agent version " + startStep.agentVersionId() + " is not found"));

    FlowSession session =
        new FlowSession(
            flowDefinition,
            flowDefinition.getVersion(),
            FlowSessionStatus.RUNNING,
            0L,
            0L);
    session.setLaunchParameters(launchParameters);
    session.setSharedContext(initializeSharedContext(sharedContext));
    session.setLaunchOverrides(toLaunchOverridesNode(launchOverrides));
    session.setStartedAt(Instant.now());
    session.setCurrentStepId(startStep.id());
    session.setChatSessionId(chatSessionId);
    flowSessionRepository.save(session);

    telemetry.sessionStarted(session.getId(), flowDefinition.getId(), flowDefinition.getVersion());

    recordEvent(session, null, FlowEventType.FLOW_STARTED, "running", null, null);

    FlowStepExecution stepExecution =
        createStepExecution(session, startStep, agentVersion, 1, buildStepInputContext(session));
    flowStepExecutionRepository.save(stepExecution);

    enqueueStepJob(session, stepExecution, 1, Instant.now());

    return session;
  }

  @Transactional
  public Optional<FlowJob> processNextJob(String workerId) {
    Optional<FlowJob> jobOptional = jobQueuePort.lockNextPending(workerId, Instant.now());
    jobOptional.ifPresent(this::executeJob);
    return jobOptional;
  }

  private void executeJob(FlowJob job) {
    FlowJobPayload payload;
    try {
      payload = objectMapper.treeToValue(job.getPayload(), FlowJobPayload.class);
    } catch (Exception exception) {
      log.error("Failed to deserialize flow job payload {}", job.getId(), exception);
      job.setStatus(FlowJobStatus.FAILED);
      jobQueuePort.save(job);
      return;
    }

    FlowStepExecution stepExecution =
        flowStepExecutionRepository
            .findById(payload.stepExecutionId())
            .orElseThrow(() -> new IllegalStateException("Step execution not found: " + payload.stepExecutionId()));

    FlowSession session = stepExecution.getFlowSession();
    FlowSessionStatus sessionStatus = session.getStatus();
    if (sessionStatus != FlowSessionStatus.RUNNING) {
      handleSessionNotRunning(job, session, stepExecution, sessionStatus);
      return;
    }

    FlowDefinitionDocument definitionDocument = flowDefinitionParser.parse(session.getFlowDefinition());
    FlowStepConfig stepConfig = definitionDocument.step(payload.stepId());

    AgentVersion agentVersion =
        agentVersionRepository
            .findById(stepConfig.agentVersionId())
            .orElseThrow(
                () ->
                    new IllegalStateException(
                    "Agent version " + stepConfig.agentVersionId() + " is not found"));

    FlowInteractionConfig interactionConfig = stepConfig.interaction();
    if (interactionConfig != null) {
      FlowInteractionRequest interactionRequest =
          flowInteractionService.ensureRequest(session, stepExecution, interactionConfig, agentVersion);
      if (interactionRequest.getStatus() == FlowInteractionStatus.PENDING) {
        enterUserInputWait(job, session, stepExecution);
        return;
      }
    }

    stepExecution.setStatus(FlowStepStatus.RUNNING);
    stepExecution.setStartedAt(Instant.now());
    stepExecution.setAgentVersion(agentVersion);
    JsonNode inputContext = buildStepInputContext(session);
    stepExecution.setPrompt(stepConfig.prompt());
    stepExecution.setInputPayload(inputContext);
    flowStepExecutionRepository.save(stepExecution);

    telemetry.stepStarted(session.getId(), stepExecution.getId(), stepExecution.getStepId(), stepExecution.getAttempt());

    try {
      ChatRequestOverrides sessionOverrides = toLaunchOverrides(session.getLaunchOverrides());
      ObjectNode stepStartedPayload =
          buildStepStartedPayload(stepExecution, agentVersion, stepConfig, sessionOverrides);
      recordEvent(session, stepExecution, FlowEventType.STEP_STARTED, "running", stepStartedPayload, null);
      String userPrompt = resolvePrompt(stepConfig, session);
      recordUserPrompt(session, stepExecution, agentVersion, userPrompt, inputContext);

      AgentInvocationRequest request =
          new AgentInvocationRequest(
              session.getId(),
              stepExecution.getId(),
              agentVersion,
              userPrompt,
              inputContext,
              session.getLaunchParameters(),
              stepConfig.overrides(),
              sessionOverrides,
              toReadInstructions(stepConfig.memoryReads()),
              List.of());

      AgentInvocationResult result = agentInvocationService.invoke(request);

      JsonNode stepOutput = applyAgentResult(stepExecution, result, session, agentVersion, stepConfig);

      FlowStepTransitions transitions = stepConfig.transitions();

      if (transitions.completeOnSuccess()) {
        session.setStatus(FlowSessionStatus.COMPLETED);
        session.setCompletedAt(Instant.now());
        recordEvent(session, stepExecution, FlowEventType.STEP_COMPLETED, "completed", cloneNode(stepOutput), result.usageCost());
        recordEvent(session, stepExecution, FlowEventType.FLOW_COMPLETED, "completed", null, result.usageCost());
        flowSessionRepository.save(session);
        telemetry.sessionCompleted(
            session.getId(),
            session.getStatus(),
            calculateDuration(session.getStartedAt(), session.getCompletedAt()));
      } else {
        String nextStepId = transitions.onSuccess();
        if (!StringUtils.hasText(nextStepId)) {
          session.setStatus(FlowSessionStatus.COMPLETED);
          session.setCompletedAt(Instant.now());
          recordEvent(session, stepExecution, FlowEventType.STEP_COMPLETED, "completed", cloneNode(stepOutput), result.usageCost());
          recordEvent(session, stepExecution, FlowEventType.FLOW_COMPLETED, "completed", null, result.usageCost());
          flowSessionRepository.save(session);
          telemetry.sessionCompleted(
              session.getId(),
              session.getStatus(),
              calculateDuration(session.getStartedAt(), session.getCompletedAt()));
        } else {
          scheduleNextStep(session, nextStepId, definitionDocument, launchContextForNext(session));
          recordEvent(session, stepExecution, FlowEventType.STEP_COMPLETED, "completed", cloneNode(stepOutput), result.usageCost());
        }
      }

      job.setStatus(FlowJobStatus.COMPLETED);
      jobQueuePort.save(job);

    } catch (RuntimeException exception) {
      handleStepFailure(job, session, stepExecution, definitionDocument, stepConfig, exception);
    }
  }

  private JsonNode applyAgentResult(
      FlowStepExecution stepExecution,
      AgentInvocationResult result,
      FlowSession session,
      AgentVersion agentVersion,
      FlowStepConfig stepConfig) {

    stepExecution.setStatus(FlowStepStatus.COMPLETED);
    stepExecution.setCompletedAt(Instant.now());

    JsonNode stepOutput = resultPayload(result);
    stepExecution.setOutputPayload(cloneNode(stepOutput));

    stepExecution.setUsage(toUsageNode(result.usageCost()));
    stepExecution.setCost(toCostNode(result.usageCost()));
    flowStepExecutionRepository.save(stepExecution);

    List<FlowMemoryVersion> updates = new ArrayList<>();
    for (MemoryWriteConfig write : stepConfig.memoryWrites()) {
      FlowMemorySourceType sourceType = mapSourceType(write.mode());
      FlowMemoryMetadata metadata =
          FlowMemoryMetadata.builder()
              .sourceType(sourceType)
              .stepId(stepExecution.getStepId())
              .stepAttempt(stepExecution.getAttempt())
              .agentVersionId(agentVersion != null ? agentVersion.getId() : null)
              .createdByStepId(stepExecution.getId())
              .build();
      if (write.mode() == MemoryWriteMode.AGENT_OUTPUT) {
        updates.add(
            flowMemoryService.append(
                session.getId(), write.channel(), cloneNode(stepOutput), metadata));
      } else if (write.mode() == MemoryWriteMode.STATIC && write.payload() != null) {
        updates.add(
            flowMemoryService.append(session.getId(), write.channel(), write.payload(), metadata));
      } else if (write.mode() == MemoryWriteMode.USER_INPUT && write.payload() != null) {
        updates.add(
            flowMemoryService.append(session.getId(), write.channel(), write.payload(), metadata));
      }
    }

    if (!updates.isEmpty()) {
      session.setCurrentMemoryVersion(updates.get(0).getVersion());
    }

    updateSharedContext(session, stepExecution, stepOutput);
    session.setStateVersion(session.getStateVersion() + 1);
    flowSessionRepository.save(session);

    Duration duration = calculateDuration(stepExecution.getStartedAt(), stepExecution.getCompletedAt());
    telemetry.stepCompleted(
        session.getId(),
        stepExecution.getId(),
        stepExecution.getStepId(),
        stepExecution.getAttempt(),
        duration,
        result.usageCost());

    return stepOutput;
  }

  private void handleStepFailure(
      FlowJob job,
      FlowSession session,
      FlowStepExecution stepExecution,
      FlowDefinitionDocument document,
      FlowStepConfig config,
      RuntimeException exception) {
    log.warn(
        "Step {} failed for session {}: {}",
        stepExecution.getStepId(),
        session.getId(),
        exception.getMessage(),
        exception);

    stepExecution.setStatus(FlowStepStatus.FAILED);
    stepExecution.setErrorMessage(exception.getMessage());
    stepExecution.setCompletedAt(Instant.now());
    flowStepExecutionRepository.save(stepExecution);

    recordEvent(
        session,
        stepExecution,
        FlowEventType.STEP_FAILED,
        "failed",
        errorPayload(exception),
        null);

    Duration failureDuration = calculateDuration(stepExecution.getStartedAt(), stepExecution.getCompletedAt());
    telemetry.stepFailed(
        session.getId(),
        stepExecution.getId(),
        stepExecution.getStepId(),
        stepExecution.getAttempt(),
        failureDuration,
        exception);

    boolean retryScheduled = false;
    if (stepExecution.getAttempt() < config.maxAttempts()) {
      retryScheduled = scheduleRetry(session, config, stepExecution, document);
    }

    if (retryScheduled) {
      job.setStatus(FlowJobStatus.COMPLETED);
      jobQueuePort.save(job);
      return;
    }

    String failureNext = config.transitions().onFailure();
    boolean failFlowOnFailure = config.transitions().failFlowOnFailure();

    if (!failFlowOnFailure) {
      enterApprovalWait(job, session, stepExecution);
      return;
    }

    if (failureNext != null && !failureNext.isBlank()) {
      scheduleNextStep(session, failureNext, document, launchContextForNext(session));
      job.setStatus(FlowJobStatus.COMPLETED);
      jobQueuePort.save(job);
      return;
    }

    session.setStatus(FlowSessionStatus.FAILED);
    session.setCompletedAt(Instant.now());
    recordEvent(session, stepExecution, FlowEventType.FLOW_FAILED, "failed", errorPayload(exception), null);
    flowSessionRepository.save(session);
    telemetry.sessionCompleted(
        session.getId(),
        session.getStatus(),
        calculateDuration(session.getStartedAt(), session.getCompletedAt()));

    job.setStatus(FlowJobStatus.FAILED);
    jobQueuePort.save(job);
  }

  private void enterApprovalWait(
      FlowJob job, FlowSession session, FlowStepExecution stepExecution) {
    stepExecution.setStatus(FlowStepStatus.WAITING_APPROVAL);
    flowStepExecutionRepository.save(stepExecution);

    session.setStatus(FlowSessionStatus.WAITING_STEP_APPROVAL);
    flowSessionRepository.save(session);

    recordEvent(session, stepExecution, FlowEventType.STEP_WAITING_APPROVAL, "waiting", null, null);
    telemetry.sessionEvent(session.getId(), "step_waiting_approval", stepExecution.getStepId());

    job.setStatus(FlowJobStatus.COMPLETED);
    job.setLockedAt(null);
    job.setLockedBy(null);
    jobQueuePort.save(job);
  }

  private boolean scheduleRetry(
      FlowSession session,
      FlowStepConfig config,
      FlowStepExecution failedExecution,
      FlowDefinitionDocument document) {
    int nextAttempt = failedExecution.getAttempt() + 1;
    FlowStepExecution retryExecution =
        createStepExecution(
            session,
            config,
            failedExecution.getAgentVersion(),
            nextAttempt,
            failedExecution.getInputPayload());
    flowStepExecutionRepository.save(retryExecution);
    enqueueStepJob(session, retryExecution, nextAttempt, Instant.now().plusSeconds(1));
    recordEvent(session, retryExecution, FlowEventType.STEP_RETRY_SCHEDULED, "scheduled", null, null);
    telemetry.retryScheduled(session.getId(), retryExecution.getStepId(), nextAttempt);
    return true;
  }

  private void scheduleNextStep(
      FlowSession session,
      String nextStepId,
      FlowDefinitionDocument document,
      JsonNode launchContext) {
    FlowStepConfig nextConfig = document.step(nextStepId);
    AgentVersion nextAgent =
        agentVersionRepository
            .findById(nextConfig.agentVersionId())
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Agent version " + nextConfig.agentVersionId() + " is not found"));

    FlowStepExecution nextExecution =
        createStepExecution(session, nextConfig, nextAgent, 1, launchContext);
    flowStepExecutionRepository.save(nextExecution);
    session.setCurrentStepId(nextConfig.id());
    flowSessionRepository.save(session);
    enqueueStepJob(session, nextExecution, 1, Instant.now());
  }

  private FlowStepExecution createStepExecution(
      FlowSession session,
      FlowStepConfig stepConfig,
      AgentVersion agentVersion,
      int attempt,
      JsonNode inputPayload) {
    FlowStepExecution execution =
        new FlowStepExecution(session, stepConfig.id(), FlowStepStatus.PENDING, attempt);
    execution.setStepName(stepConfig.name());
    execution.setAgentVersion(agentVersion);
    execution.setInputPayload(inputPayload);
    return execution;
  }

  private void enqueueStepJob(
      FlowSession session, FlowStepExecution execution, int attempt, Instant scheduledAt) {
    FlowJobPayload payload =
        new FlowJobPayload(session.getId(), execution.getId(), execution.getStepId(), attempt);
    jobQueuePort.enqueueStepJob(session, execution, payload, scheduledAt);
  }

  private void handleSessionNotRunning(
      FlowJob job,
      FlowSession session,
      FlowStepExecution stepExecution,
      FlowSessionStatus status) {
    if (status == FlowSessionStatus.PAUSED
        || status == FlowSessionStatus.PENDING
        || status == FlowSessionStatus.WAITING_USER_INPUT) {
      log.debug(
          "Flow session {} is {} — returning job {} to the pending queue",
          session.getId(),
          status,
          job.getId());
      resetJobLock(job, FlowJobStatus.PENDING);
      return;
    }

    log.debug(
        "Flow session {} is {} — cancelling job {}",
        session.getId(),
        status,
        job.getId());
    resetJobLock(job, FlowJobStatus.CANCELLED);
    stepExecution.setStatus(FlowStepStatus.CANCELLED);
    stepExecution.setCompletedAt(Instant.now());
    flowStepExecutionRepository.save(stepExecution);
    recordEvent(session, stepExecution, FlowEventType.STEP_SKIPPED, status.name().toLowerCase(), null, null);
  }

  private void resetJobLock(FlowJob job, FlowJobStatus status) {
    job.setStatus(status);
    job.setLockedAt(null);
    job.setLockedBy(null);
    jobQueuePort.save(job);
  }

  private void enterUserInputWait(
      FlowJob job, FlowSession session, FlowStepExecution stepExecution) {
    log.debug(
        "Flow session {} waiting for user input on step {}; completing job {}",
        session.getId(),
        stepExecution.getStepId(),
        job.getId());
    resetJobLock(job, FlowJobStatus.COMPLETED);
  }

  private List<MemoryReadInstruction> toReadInstructions(List<MemoryReadConfig> configs) {
    if (configs == null || configs.isEmpty()) {
      return List.of();
    }
    List<MemoryReadInstruction> instructions = new ArrayList<>(configs.size());
    for (MemoryReadConfig config : configs) {
      instructions.add(new MemoryReadInstruction(config.channel(), config.limit()));
    }
    return instructions;
  }

  private String resolvePrompt(FlowStepConfig stepConfig, FlowSession session) {
    if (StringUtils.hasText(stepConfig.prompt())) {
      return stepConfig.prompt();
    }
    return stepConfig.name() != null ? stepConfig.name() : "Provide response";
  }

  private JsonNode launchContextForNext(FlowSession session) {
    return buildStepInputContext(session);
  }

  private JsonNode initializeSharedContext(JsonNode sharedContext) {
    ObjectNode context = objectMapper.createObjectNode();
    context.set("initial", cloneNode(sharedContext));
    context.set("current", cloneNode(sharedContext));
    context.set("steps", objectMapper.createObjectNode());
    context.set("lastOutput", objectMapper.nullNode());
    context.putNull("lastStepId");
    context.put("version", 0);
    return context;
  }

  private JsonNode buildStepInputContext(FlowSession session) {
    ObjectNode input = objectMapper.createObjectNode();
    if (session.getLaunchParameters() != null && !session.getLaunchParameters().isNull()) {
      input.set("launchParameters", cloneNode(session.getLaunchParameters()));
    }

    JsonNode shared = session.getSharedContext();
    if (shared != null && !shared.isNull()) {
      input.set("sharedContext", cloneNode(shared));
      JsonNode initial = shared.get("initial");
      if (initial != null) {
        input.set("initialContext", cloneNode(initial));
      }
      JsonNode lastOutput = shared.get("lastOutput");
      if (lastOutput != null && !lastOutput.isNull()) {
        input.set("lastOutput", cloneNode(lastOutput));
      }
      JsonNode current = shared.get("current");
      if (current != null && !current.isNull()) {
        input.set("currentContext", cloneNode(current));
      }
    }
    return input;
  }

  private void updateSharedContext(
      FlowSession session, FlowStepExecution stepExecution, JsonNode stepOutput) {
    JsonNode shared = session.getSharedContext();
    ObjectNode context =
        shared != null && shared.isObject() ? (ObjectNode) shared : objectMapper.createObjectNode();

    ObjectNode stepsNode = context.with("steps");
    stepsNode.set(stepExecution.getStepId(), cloneNode(stepOutput));
    context.put("lastStepId", stepExecution.getStepId());
    context.set("lastOutput", cloneNode(stepOutput));
    context.set("current", cloneNode(stepOutput));
    context.put("version", context.path("version").asInt(0) + 1);
    session.setSharedContext(context);
  }

  private void recordEvent(
      FlowSession session,
      FlowStepExecution stepExecution,
      FlowEventType eventType,
      String status,
      JsonNode payload,
      UsageCostEstimate usageCost) {
    JsonNode payloadWithMetadata = attachStepMetadata(stepExecution, payload);
    JsonNode payloadWithContext = enrichPayloadWithContext(session, payloadWithMetadata);
    FlowEvent event = new FlowEvent(session, eventType, status, payloadWithContext);
    if (stepExecution != null) {
      event.setFlowStepExecution(stepExecution);
    }
    event.setTraceId(session.getId().toString());
    if (stepExecution != null && stepExecution.getId() != null) {
      event.setSpanId(stepExecution.getId().toString());
    } else {
      event.setSpanId(session.getId().toString());
    }
    if (usageCost != null) {
      event.setCost(usageCost.totalCost());
      event.setTokensPrompt(usageCost.promptTokens());
      event.setTokensCompletion(usageCost.completionTokens());
      event.setUsageSource(usageCost.source());
    }
    flowEventRepository.save(event);
  }

  private JsonNode resultPayload(AgentInvocationResult result) {
    ObjectNode node = objectMapper.createObjectNode();
    node.put("content", result.content());
    UsageCostEstimate usageCost = result.usageCost();
    if (usageCost != null) {
      ObjectNode usageNode = node.putObject("usage");
      if (usageCost.promptTokens() != null) {
        usageNode.put("promptTokens", usageCost.promptTokens());
      }
      if (usageCost.completionTokens() != null) {
        usageNode.put("completionTokens", usageCost.completionTokens());
      }
      if (usageCost.totalTokens() != null) {
        usageNode.put("totalTokens", usageCost.totalTokens());
      }

      ObjectNode costNode = node.putObject("cost");
      if (usageCost.inputCost() != null) {
        costNode.put("input", usageCost.inputCost().doubleValue());
      }
      if (usageCost.outputCost() != null) {
        costNode.put("output", usageCost.outputCost().doubleValue());
      }
      if (usageCost.totalCost() != null) {
        costNode.put("total", usageCost.totalCost().doubleValue());
      }
      if (usageCost.currency() != null) {
        costNode.put("currency", usageCost.currency());
      }
    }
    ObjectNode requestNode = objectMapper.createObjectNode();
    boolean hasRequestData = false;
    if (result.providerSelection() != null) {
      ObjectNode providerNode = requestNode.putObject("provider");
      providerNode.put("providerId", result.providerSelection().providerId());
      providerNode.put("modelId", result.providerSelection().modelId());
      hasRequestData = true;
    }
    JsonNode overridesNode = toLaunchOverridesNode(result.appliedOverrides());
    if (overridesNode != null) {
      requestNode.set("overrides", overridesNode);
      hasRequestData = true;
    }
    if (StringUtils.hasText(result.systemPrompt())) {
      requestNode.put("systemPrompt", result.systemPrompt());
      hasRequestData = true;
    }
    if (result.memorySnapshots() != null && !result.memorySnapshots().isEmpty()) {
      ArrayNode memoryArray = requestNode.putArray("memorySnapshots");
      result.memorySnapshots().forEach(memoryArray::add);
      hasRequestData = true;
    }
    if (StringUtils.hasText(result.userMessage())) {
      requestNode.put("userMessage", result.userMessage());
      hasRequestData = true;
    }
    if (hasRequestData) {
      node.set("request", requestNode);
    }
    return node;
  }

  private JsonNode toUsageNode(UsageCostEstimate usageCost) {
    if (usageCost == null) {
      return null;
    }
    ObjectNode node = objectMapper.createObjectNode();
    if (usageCost.promptTokens() != null) {
      node.put("promptTokens", usageCost.promptTokens());
    }
    if (usageCost.completionTokens() != null) {
      node.put("completionTokens", usageCost.completionTokens());
    }
    if (usageCost.totalTokens() != null) {
      node.put("totalTokens", usageCost.totalTokens());
    }
    return node;
  }

  private JsonNode toCostNode(UsageCostEstimate usageCost) {
    if (usageCost == null) {
      return null;
    }
    ObjectNode node = objectMapper.createObjectNode();
    if (usageCost.inputCost() != null) {
      node.put("input", usageCost.inputCost().doubleValue());
    }
    if (usageCost.outputCost() != null) {
      node.put("output", usageCost.outputCost().doubleValue());
    }
    if (usageCost.totalCost() != null) {
      node.put("total", usageCost.totalCost().doubleValue());
    }
    if (usageCost.currency() != null) {
      node.put("currency", usageCost.currency());
    }
    return node;
  }

  private JsonNode errorPayload(Throwable throwable) {
    ObjectNode node = objectMapper.createObjectNode();
    node.put("message", throwable != null ? throwable.getMessage() : "Unknown error");
    return node;
  }

  private JsonNode enrichPayloadWithContext(FlowSession session, JsonNode payload) {
    ObjectNode contextNode = buildContextNode(session);
    boolean hasContext = contextNode != null && contextNode.size() > 0;

    if (!hasContext) {
      return payload;
    }

    if (payload instanceof ObjectNode objectNode) {
      objectNode.set("context", contextNode);
      return objectNode;
    }

    if (payload == null || payload.isNull()) {
      return contextNode;
    }

    ObjectNode wrapper = objectMapper.createObjectNode();
    wrapper.set("data", cloneNode(payload));
    wrapper.set("context", contextNode);
    return wrapper;
  }

  private JsonNode attachStepMetadata(FlowStepExecution stepExecution, JsonNode payload) {
    if (stepExecution == null) {
      return payload;
    }
    ObjectNode metadata = objectMapper.createObjectNode();
    if (stepExecution.getId() != null) {
      metadata.put("stepExecutionId", stepExecution.getId().toString());
    }
    metadata.put("stepId", stepExecution.getStepId());
    if (StringUtils.hasText(stepExecution.getStepName())) {
      metadata.put("stepName", stepExecution.getStepName());
    }
    metadata.put("attempt", stepExecution.getAttempt());
    if (stepExecution.getStatus() != null) {
      metadata.put("status", stepExecution.getStatus().name());
    }
    if (StringUtils.hasText(stepExecution.getPrompt())) {
      metadata.put("prompt", stepExecution.getPrompt());
    }
    AgentVersion agentVersion = stepExecution.getAgentVersion();
    if (agentVersion != null) {
      metadata.set("agentVersion", buildAgentVersionNode(agentVersion));
    }
    if (metadata.size() == 0) {
      return payload;
    }
    if (payload instanceof ObjectNode objectNode) {
      if (!objectNode.has("step")) {
        objectNode.set("step", metadata);
      }
      return objectNode;
    }
    if (payload == null || payload.isNull()) {
      return metadata;
    }
    ObjectNode wrapper = objectMapper.createObjectNode();
    wrapper.set("data", cloneNode(payload));
    wrapper.set("step", metadata);
    return wrapper;
  }

  private ObjectNode buildStepStartedPayload(
      FlowStepExecution stepExecution,
      AgentVersion agentVersion,
      FlowStepConfig stepConfig,
      ChatRequestOverrides sessionOverrides) {
    ObjectNode node = objectMapper.createObjectNode();
    if (stepExecution.getId() != null) {
      node.put("stepExecutionId", stepExecution.getId().toString());
    }
    node.put("stepId", stepExecution.getStepId());
    if (StringUtils.hasText(stepExecution.getStepName())) {
      node.put("stepName", stepExecution.getStepName());
    }
    node.put("attempt", stepExecution.getAttempt());
    if (StringUtils.hasText(stepExecution.getPrompt())) {
      node.put("prompt", stepExecution.getPrompt());
    }
    if (agentVersion != null) {
      node.set("agentVersion", buildAgentVersionNode(agentVersion));
    }
    if (stepConfig != null && stepConfig.overrides() != null) {
      JsonNode stepOverridesNode = toLaunchOverridesNode(stepConfig.overrides());
      if (stepOverridesNode != null) {
        node.set("stepOverrides", stepOverridesNode);
      }
    }
    JsonNode sessionOverridesNode = toLaunchOverridesNode(sessionOverrides);
    if (sessionOverridesNode != null) {
      node.set("sessionOverrides", sessionOverridesNode);
    }
    return node;
  }

  private ObjectNode buildAgentVersionNode(AgentVersion agentVersion) {
    ObjectNode agentNode = objectMapper.createObjectNode();
    if (agentVersion.getId() != null) {
      agentNode.put("id", agentVersion.getId().toString());
    }
    agentNode.put("version", agentVersion.getVersion());
    if (agentVersion.getProviderType() != null) {
      agentNode.put("providerType", agentVersion.getProviderType().name());
    }
    agentNode.put("providerId", agentVersion.getProviderId());
    agentNode.put("modelId", agentVersion.getModelId());
    agentNode.put("syncOnly", agentVersion.isSyncOnly());
    if (agentVersion.getMaxTokens() != null) {
      agentNode.put("maxTokens", agentVersion.getMaxTokens());
    }
    if (StringUtils.hasText(agentVersion.getSystemPrompt())) {
      agentNode.put("systemPrompt", agentVersion.getSystemPrompt());
    }
    if (agentVersion.getDefaultOptions() != null && !agentVersion.getDefaultOptions().isNull()) {
      agentNode.set("defaultOptions", cloneNode(agentVersion.getDefaultOptions()));
    }
    return agentNode;
  }

  private ObjectNode buildContextNode(FlowSession session) {
    ObjectNode node = objectMapper.createObjectNode();
    if (session.getLaunchParameters() != null && !session.getLaunchParameters().isNull()) {
      node.set("launchParameters", cloneNode(session.getLaunchParameters()));
    }
    if (session.getLaunchOverrides() != null && !session.getLaunchOverrides().isNull()) {
      node.set("launchOverrides", cloneNode(session.getLaunchOverrides()));
    }
    return node.size() > 0 ? node : null;
  }

  private void recordUserPrompt(
      FlowSession session,
      FlowStepExecution stepExecution,
      AgentVersion agentVersion,
      String userPrompt,
      JsonNode inputContext) {
    if (!StringUtils.hasText(userPrompt)) {
      return;
    }
    FlowMemoryMetadata metadata =
        FlowMemoryMetadata.builder()
            .sourceType(FlowMemorySourceType.USER_INPUT)
            .stepId(stepExecution.getStepId())
            .stepAttempt(stepExecution.getAttempt())
            .agentVersionId(agentVersion != null ? agentVersion.getId() : null)
            .createdByStepId(stepExecution.getId())
            .build();

    ObjectNode payload = objectMapper.createObjectNode();
    payload.put("prompt", userPrompt);
    if (inputContext != null) {
      payload.set("context", inputContext.deepCopy());
    }
    flowMemoryService.append(session.getId(), "conversation", payload, metadata);
  }

  private JsonNode cloneNode(JsonNode node) {
    if (node == null || node.isNull()) {
      return objectMapper.nullNode();
    }
    return node.deepCopy();
  }

  private JsonNode toLaunchOverridesNode(ChatRequestOverrides overrides) {
    if (overrides == null) {
      return null;
    }
    if (overrides.temperature() == null
        && overrides.topP() == null
        && overrides.maxTokens() == null) {
      return null;
    }
    return objectMapper.valueToTree(overrides);
  }

  private ChatRequestOverrides toLaunchOverrides(JsonNode overridesNode) {
    if (overridesNode == null || overridesNode.isNull()) {
      return null;
    }
    try {
      return objectMapper.treeToValue(overridesNode, ChatRequestOverrides.class);
    } catch (Exception exception) {
      log.warn("Failed to deserialize launch overrides for session {}", overridesNode, exception);
      return null;
    }
  }

  private Duration calculateDuration(Instant start, Instant end) {
    if (start == null || end == null) {
      return null;
    }
    return Duration.between(start, end);
  }

  private FlowMemorySourceType mapSourceType(MemoryWriteMode mode) {
    if (mode == null) {
      return FlowMemorySourceType.SYSTEM;
    }
    return switch (mode) {
      case USER_INPUT -> FlowMemorySourceType.USER_INPUT;
      case AGENT_OUTPUT -> FlowMemorySourceType.AGENT_OUTPUT;
      case STATIC -> FlowMemorySourceType.SYSTEM;
    };
  }
}
