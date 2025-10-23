package com.aiadvent.backend.flow.service;

import com.aiadvent.backend.chat.provider.model.UsageCostEstimate;
import com.aiadvent.backend.flow.config.FlowDefinitionDocument;
import com.aiadvent.backend.flow.config.FlowDefinitionParser;
import com.aiadvent.backend.flow.config.FlowStepConfig;
import com.aiadvent.backend.flow.config.FlowStepTransitions;
import com.aiadvent.backend.flow.config.MemoryReadConfig;
import com.aiadvent.backend.flow.config.MemoryWriteConfig;
import com.aiadvent.backend.flow.config.MemoryWriteMode;
import com.aiadvent.backend.flow.domain.AgentVersion;
import com.aiadvent.backend.flow.domain.FlowDefinition;
import com.aiadvent.backend.flow.domain.FlowEvent;
import com.aiadvent.backend.flow.domain.FlowEventType;
import com.aiadvent.backend.flow.domain.FlowJob;
import com.aiadvent.backend.flow.domain.FlowJobStatus;
import com.aiadvent.backend.flow.domain.FlowMemoryVersion;
import com.aiadvent.backend.flow.domain.FlowSession;
import com.aiadvent.backend.flow.domain.FlowSessionStatus;
import com.aiadvent.backend.flow.domain.FlowStepExecution;
import com.aiadvent.backend.flow.domain.FlowStepStatus;
import com.aiadvent.backend.flow.job.FlowJobPayload;
import com.aiadvent.backend.flow.job.JobQueuePort;
import com.aiadvent.backend.flow.memory.FlowMemoryService;
import com.aiadvent.backend.flow.persistence.AgentVersionRepository;
import com.aiadvent.backend.flow.persistence.FlowDefinitionRepository;
import com.aiadvent.backend.flow.persistence.FlowEventRepository;
import com.aiadvent.backend.flow.persistence.FlowSessionRepository;
import com.aiadvent.backend.flow.persistence.FlowStepExecutionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
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

  private final FlowDefinitionRepository flowDefinitionRepository;
  private final FlowDefinitionParser flowDefinitionParser;
  private final FlowSessionRepository flowSessionRepository;
  private final FlowStepExecutionRepository flowStepExecutionRepository;
  private final FlowEventRepository flowEventRepository;
  private final AgentVersionRepository agentVersionRepository;
  private final AgentInvocationService agentInvocationService;
  private final FlowMemoryService flowMemoryService;
  private final JobQueuePort jobQueuePort;
  private final ObjectMapper objectMapper;

  public AgentOrchestratorService(
      FlowDefinitionRepository flowDefinitionRepository,
      FlowDefinitionParser flowDefinitionParser,
      FlowSessionRepository flowSessionRepository,
      FlowStepExecutionRepository flowStepExecutionRepository,
      FlowEventRepository flowEventRepository,
      AgentVersionRepository agentVersionRepository,
      AgentInvocationService agentInvocationService,
      FlowMemoryService flowMemoryService,
      JobQueuePort jobQueuePort,
      ObjectMapper objectMapper) {
    this.flowDefinitionRepository = flowDefinitionRepository;
    this.flowDefinitionParser = flowDefinitionParser;
    this.flowSessionRepository = flowSessionRepository;
    this.flowStepExecutionRepository = flowStepExecutionRepository;
    this.flowEventRepository = flowEventRepository;
    this.agentVersionRepository = agentVersionRepository;
    this.agentInvocationService = agentInvocationService;
    this.flowMemoryService = flowMemoryService;
    this.jobQueuePort = jobQueuePort;
    this.objectMapper = objectMapper;
  }

  @Transactional
  public FlowSession start(UUID flowDefinitionId, JsonNode launchParameters, JsonNode sharedContext) {
    FlowDefinition flowDefinition =
        flowDefinitionRepository
            .findById(flowDefinitionId)
            .orElseThrow(() -> new IllegalArgumentException("Flow definition not found: " + flowDefinitionId));

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
    session.setSharedContext(sharedContext);
    session.setStartedAt(Instant.now());
    session.setCurrentStepId(startStep.id());
    flowSessionRepository.save(session);

    recordEvent(session, null, FlowEventType.FLOW_STARTED, "running", null, null);

    FlowStepExecution stepExecution =
        createStepExecution(session, startStep, agentVersion, 1, launchParameters);
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
    FlowDefinitionDocument definitionDocument = flowDefinitionParser.parse(session.getFlowDefinition());
    FlowStepConfig stepConfig = definitionDocument.step(payload.stepId());

    AgentVersion agentVersion =
        agentVersionRepository
            .findById(stepConfig.agentVersionId())
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Agent version " + stepConfig.agentVersionId() + " is not found"));

    stepExecution.setStatus(FlowStepStatus.RUNNING);
    stepExecution.setStartedAt(Instant.now());
    stepExecution.setAgentVersion(agentVersion);
    stepExecution.setPrompt(stepConfig.prompt());
    stepExecution.setInputPayload(session.getSharedContext());
    flowStepExecutionRepository.save(stepExecution);

    recordEvent(session, stepExecution, FlowEventType.STEP_STARTED, "running", null, null);

    try {
      AgentInvocationRequest request =
          new AgentInvocationRequest(
              session.getId(),
              stepExecution.getId(),
              agentVersion,
              resolvePrompt(stepConfig, session),
              session.getSharedContext(),
              stepConfig.overrides(),
              toReadInstructions(stepConfig.memoryReads()),
              List.of());

      AgentInvocationResult result = agentInvocationService.invoke(request);

      applyAgentResult(stepExecution, result, session, stepConfig);

      FlowStepTransitions transitions = stepConfig.transitions();

      if (transitions.completeOnSuccess()) {
        session.setStatus(FlowSessionStatus.COMPLETED);
        session.setCompletedAt(Instant.now());
        recordEvent(session, stepExecution, FlowEventType.STEP_COMPLETED, "completed", resultPayload(result), result.usageCost());
        recordEvent(session, stepExecution, FlowEventType.FLOW_COMPLETED, "completed", null, result.usageCost());
        flowSessionRepository.save(session);
      } else {
        String nextStepId = transitions.onSuccess();
        if (!StringUtils.hasText(nextStepId)) {
          session.setStatus(FlowSessionStatus.COMPLETED);
          session.setCompletedAt(Instant.now());
          recordEvent(session, stepExecution, FlowEventType.STEP_COMPLETED, "completed", resultPayload(result), result.usageCost());
          recordEvent(session, stepExecution, FlowEventType.FLOW_COMPLETED, "completed", null, result.usageCost());
          flowSessionRepository.save(session);
        } else {
          scheduleNextStep(session, nextStepId, definitionDocument, launchContextForNext(session));
          recordEvent(session, stepExecution, FlowEventType.STEP_COMPLETED, "completed", resultPayload(result), result.usageCost());
        }
      }

      job.setStatus(FlowJobStatus.COMPLETED);
      jobQueuePort.save(job);

    } catch (RuntimeException exception) {
      handleStepFailure(job, session, stepExecution, definitionDocument, stepConfig, exception);
    }
  }

  private void applyAgentResult(
      FlowStepExecution stepExecution,
      AgentInvocationResult result,
      FlowSession session,
      FlowStepConfig stepConfig) {

    stepExecution.setStatus(FlowStepStatus.COMPLETED);
    stepExecution.setCompletedAt(Instant.now());

    ObjectNode output = objectMapper.createObjectNode();
    output.put("content", result.content());
    stepExecution.setOutputPayload(output);

    stepExecution.setUsage(toUsageNode(result.usageCost()));
    stepExecution.setCost(toCostNode(result.usageCost()));
    flowStepExecutionRepository.save(stepExecution);

    List<FlowMemoryVersion> updates = new ArrayList<>();
    for (MemoryWriteConfig write : stepConfig.memoryWrites()) {
      if (write.mode() == MemoryWriteMode.AGENT_OUTPUT) {
        updates.add(
            flowMemoryService.append(
                session.getId(),
                write.channel(),
                resultPayload(result),
                stepExecution.getId()));
      } else if (write.mode() == MemoryWriteMode.STATIC && write.payload() != null) {
        updates.add(
            flowMemoryService.append(
                session.getId(), write.channel(), write.payload(), stepExecution.getId()));
      }
    }

    if (!updates.isEmpty()) {
      session.setCurrentMemoryVersion(updates.get(0).getVersion());
    }

    session.setStateVersion(session.getStateVersion() + 1);
    flowSessionRepository.save(session);
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

    boolean retryScheduled = false;
    if (stepExecution.getAttempt() < config.maxAttempts()) {
      retryScheduled = scheduleRetry(session, config, stepExecution, document);
    }

    if (!retryScheduled) {
      session.setStatus(FlowSessionStatus.FAILED);
      session.setCompletedAt(Instant.now());
      recordEvent(session, stepExecution, FlowEventType.FLOW_FAILED, "failed", errorPayload(exception), null);
      flowSessionRepository.save(session);
    }

    job.setStatus(retryScheduled ? FlowJobStatus.COMPLETED : FlowJobStatus.FAILED);
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
    recordEvent(session, retryExecution, FlowEventType.STEP_STARTED, "scheduled", null, null);
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
    return session.getSharedContext();
  }

  private void recordEvent(
      FlowSession session,
      FlowStepExecution stepExecution,
      FlowEventType eventType,
      String status,
      JsonNode payload,
      UsageCostEstimate usageCost) {
    FlowEvent event = new FlowEvent(session, eventType, status, payload);
    if (stepExecution != null) {
      event.setFlowStepExecution(stepExecution);
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
}
