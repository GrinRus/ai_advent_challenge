package com.aiadvent.backend.flow.service;

import com.aiadvent.backend.chat.provider.model.ChatRequestOverrides;
import com.aiadvent.backend.chat.provider.model.UsageCostEstimate;
import com.aiadvent.backend.flow.agent.options.AgentInvocationOptions;
import com.aiadvent.backend.flow.blueprint.FlowBlueprintCompiler;
import com.aiadvent.backend.flow.config.FlowDefinitionDocument;
import com.aiadvent.backend.flow.config.FlowStepConfig;
import com.aiadvent.backend.flow.config.FlowStepTransitions;
import com.aiadvent.backend.flow.config.MemoryReadConfig;
import com.aiadvent.backend.flow.config.MemoryWriteConfig;
import com.aiadvent.backend.flow.config.MemoryWriteMode;
import com.aiadvent.backend.flow.config.FlowInteractionConfig;
import com.aiadvent.backend.flow.execution.model.FlowEventPayload;
import com.aiadvent.backend.flow.execution.model.FlowStepInputPayload;
import com.aiadvent.backend.flow.execution.model.FlowStepOutputPayload;
import com.aiadvent.backend.flow.execution.model.FlowUsagePayload;
import com.aiadvent.backend.flow.execution.model.FlowCostPayload;
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
import com.aiadvent.backend.flow.memory.FlowMemoryChannels;
import com.aiadvent.backend.flow.memory.FlowMemoryMetadata;
import com.aiadvent.backend.flow.memory.FlowMemoryService;
import com.aiadvent.backend.flow.memory.FlowMemorySourceType;
import com.aiadvent.backend.flow.telemetry.FlowTelemetryService;
import com.aiadvent.backend.flow.persistence.AgentVersionRepository;
import com.aiadvent.backend.flow.persistence.FlowEventRepository;
import com.aiadvent.backend.flow.persistence.FlowSessionRepository;
import com.aiadvent.backend.flow.persistence.FlowStepExecutionRepository;
import com.aiadvent.backend.flow.session.model.FlowLaunchParameters;
import com.aiadvent.backend.flow.session.model.FlowOverrides;
import com.aiadvent.backend.flow.session.model.FlowSharedContext;
import com.aiadvent.backend.flow.telemetry.FlowTraceFormatter;
import com.aiadvent.backend.flow.service.payload.FlowPayloadMapper;
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
  private final FlowBlueprintCompiler flowBlueprintCompiler;
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
  private final FlowPayloadMapper flowPayloadMapper;

  public AgentOrchestratorService(
      FlowDefinitionService flowDefinitionService,
      FlowBlueprintCompiler flowBlueprintCompiler,
      FlowSessionRepository flowSessionRepository,
      FlowStepExecutionRepository flowStepExecutionRepository,
      FlowEventRepository flowEventRepository,
      AgentVersionRepository agentVersionRepository,
      AgentInvocationService agentInvocationService,
      FlowMemoryService flowMemoryService,
      FlowInteractionService flowInteractionService,
      JobQueuePort jobQueuePort,
      ObjectMapper objectMapper,
      FlowTelemetryService telemetry,
      FlowPayloadMapper flowPayloadMapper) {
    this.flowDefinitionService = flowDefinitionService;
    this.flowBlueprintCompiler = flowBlueprintCompiler;
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
    this.flowPayloadMapper = flowPayloadMapper;
  }

  @Transactional
  public FlowSession start(
      UUID flowDefinitionId,
      FlowLaunchParameters launchParameters,
      FlowSharedContext sharedContext,
      FlowOverrides launchOverrides,
      UUID chatSessionId) {
    FlowDefinition flowDefinition =
        flowDefinitionService.getActivePublishedDefinition(flowDefinitionId);

    FlowDefinitionDocument document = flowBlueprintCompiler.compile(flowDefinition);
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
    FlowLaunchParameters effectiveParameters =
        launchParameters != null ? launchParameters : FlowLaunchParameters.empty();
    FlowSharedContext requestedContext =
        sharedContext != null ? sharedContext : FlowSharedContext.empty();
    FlowOverrides effectiveOverrides =
        launchOverrides != null ? launchOverrides : FlowOverrides.empty();

    session.setLaunchParameters(effectiveParameters);
    JsonNode contextNode = !requestedContext.isEmpty() ? requestedContext.asJson() : null;
    session.setSharedContext(flowPayloadMapper.initializeSharedContext(contextNode));
    session.setLaunchOverrides(effectiveOverrides);
    session.setStartedAt(Instant.now());
    session.setCurrentStepId(startStep.id());
    session.setChatSessionId(chatSessionId);
    flowSessionRepository.save(session);

    telemetry.sessionStarted(session.getId(), flowDefinition.getId(), flowDefinition.getVersion());

    recordEvent(session, null, FlowEventType.FLOW_STARTED, "running", null, null);

    FlowStepInputPayload initialInput = flowPayloadMapper.buildStepInputPayload(session);
    FlowStepExecution stepExecution =
        createStepExecution(session, startStep, agentVersion, 1, initialInput);
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

    FlowDefinitionDocument definitionDocument =
        flowBlueprintCompiler.compile(session.getFlowDefinition());
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
    FlowStepInputPayload baseInputPayload = flowPayloadMapper.buildStepInputPayload(session);
    FlowStepInputPayload inputPayload =
        flowPayloadMapper.mergeInteractionPayload(baseInputPayload, stepExecution.getInputPayload());
    JsonNode inputContext = inputPayload.asJson();
    stepExecution.setPrompt(stepConfig.prompt());
    stepExecution.setInputPayload(inputPayload);
    flowStepExecutionRepository.save(stepExecution);

    telemetry.stepStarted(session.getId(), stepExecution.getId(), stepExecution.getStepId(), stepExecution.getAttempt());

    if (log.isDebugEnabled()) {
      log.debug(
          "{}",
          FlowTraceFormatter.stepDispatch(
              session,
              stepExecution,
              agentVersion,
              stepConfig,
              session.getLaunchOverrides()));
    }

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
              session.getLaunchParameters().asJson(),
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
    stepExecution.setOutputPayload(FlowStepOutputPayload.from(stepOutput));

    stepExecution.setUsage(flowPayloadMapper.usagePayload(result.usageCost()));
    stepExecution.setCost(flowPayloadMapper.costPayload(result.usageCost()));
    flowStepExecutionRepository.save(stepExecution);

    List<FlowMemoryVersion> updates = new ArrayList<>();
    boolean agentOutputWrittenToConversation = false;
    FlowMemoryMetadata lastAgentOutputMetadata = null;
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
        lastAgentOutputMetadata = metadata;
        if (FlowMemoryChannels.CONVERSATION.equalsIgnoreCase(write.channel())) {
          agentOutputWrittenToConversation = true;
        }
      } else if (write.mode() == MemoryWriteMode.STATIC && write.payload() != null) {
        updates.add(
            flowMemoryService.append(session.getId(), write.channel(), write.payload(), metadata));
      } else if (write.mode() == MemoryWriteMode.USER_INPUT && write.payload() != null) {
        updates.add(
            flowMemoryService.append(session.getId(), write.channel(), write.payload(), metadata));
      }
    }

    if (lastAgentOutputMetadata != null && !agentOutputWrittenToConversation) {
      updates.add(
          flowMemoryService.append(
              session.getId(),
              FlowMemoryChannels.CONVERSATION,
              cloneNode(stepOutput),
              lastAgentOutputMetadata));
    }

    if (!updates.isEmpty()) {
      session.setCurrentMemoryVersion(updates.get(0).getVersion());
    }

    session.setSharedContext(flowPayloadMapper.applyStepOutput(session, stepExecution, stepOutput));
    session.setStateVersion(session.getStateVersion() + 1);
    flowSessionRepository.save(session);

    List<FlowMemoryVersion> traceUpdates =
        result.memoryUpdates() != null && !result.memoryUpdates().isEmpty() ? result.memoryUpdates() : updates;

    Duration duration = calculateDuration(stepExecution.getStartedAt(), stepExecution.getCompletedAt());
    telemetry.stepCompleted(
        session.getId(),
        stepExecution.getId(),
        stepExecution.getStepId(),
        stepExecution.getAttempt(),
        duration,
        result.usageCost());

    if (log.isInfoEnabled()) {
      log.info(
          "{}",
          FlowTraceFormatter.stepCompletion(
              session,
              stepExecution,
              agentVersion,
              result.usageCost(),
              traceUpdates,
              stepOutput,
              duration));
    }

    return stepOutput;
  }

  private void handleStepFailure(
      FlowJob job,
      FlowSession session,
      FlowStepExecution stepExecution,
      FlowDefinitionDocument document,
      FlowStepConfig config,
      RuntimeException exception) {
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

    if (log.isWarnEnabled()) {
      log.warn(
          "{}",
          FlowTraceFormatter.stepFailure(
              session, stepExecution, stepExecution.getAgentVersion(), exception, failureDuration),
          exception);
    }

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
      FlowStepInputPayload launchContext) {
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
      FlowStepInputPayload inputPayload) {
    FlowStepExecution execution =
        new FlowStepExecution(session, stepConfig.id(), FlowStepStatus.PENDING, attempt);
    execution.setStepName(stepConfig.name());
    execution.setAgentVersion(agentVersion);
    execution.setInputPayload(inputPayload != null ? inputPayload : FlowStepInputPayload.empty());
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

  private FlowStepInputPayload launchContextForNext(FlowSession session) {
    return flowPayloadMapper.buildStepInputPayload(session);
  }

  private void recordEvent(
      FlowSession session,
      FlowStepExecution stepExecution,
      FlowEventType eventType,
      String status,
      JsonNode payload,
      UsageCostEstimate usageCost) {
    FlowEventPayload mappedPayload =
        stepExecution != null
            ? flowPayloadMapper.eventPayload(session, stepExecution, payload)
            : flowPayloadMapper.eventPayload(session, payload);
    FlowEvent event = new FlowEvent(session, eventType, status, mappedPayload);
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
    if (result.selectedToolCodes() != null && !result.selectedToolCodes().isEmpty()) {
      ArrayNode toolArray = requestNode.putArray("selectedTools");
      result.selectedToolCodes().forEach(toolArray::add);
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
    AgentInvocationOptions invocationOptions = agentVersion.getInvocationOptions();
    if (invocationOptions != null && !AgentInvocationOptions.empty().equals(invocationOptions)) {
      agentNode.set("invocationOptions", objectMapper.valueToTree(invocationOptions));
    }
    return agentNode;
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
    flowMemoryService.append(session.getId(), FlowMemoryChannels.CONVERSATION, payload, metadata);
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

  private ChatRequestOverrides toLaunchOverrides(FlowOverrides overrides) {
    if (overrides == null || overrides.isEmpty()) {
      return null;
    }
    try {
      return objectMapper.treeToValue(overrides.asJson(), ChatRequestOverrides.class);
    } catch (Exception exception) {
      log.warn("Failed to deserialize launch overrides", exception);
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
