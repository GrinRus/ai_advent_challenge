package com.aiadvent.backend.flow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aiadvent.backend.chat.provider.model.UsageCostEstimate;
import com.aiadvent.backend.flow.config.FlowDefinitionDocument;
import com.aiadvent.backend.flow.config.FlowDefinitionParser;
import com.aiadvent.backend.flow.config.FlowStepConfig;
import com.aiadvent.backend.flow.config.FlowStepTransitions;
import com.aiadvent.backend.flow.domain.AgentDefinition;
import com.aiadvent.backend.flow.domain.AgentVersion;
import com.aiadvent.backend.flow.domain.AgentVersionStatus;
import com.aiadvent.backend.flow.domain.FlowDefinition;
import com.aiadvent.backend.flow.domain.FlowDefinitionStatus;
import com.aiadvent.backend.flow.domain.FlowEvent;
import com.aiadvent.backend.flow.domain.FlowEventType;
import com.aiadvent.backend.flow.domain.FlowJob;
import com.aiadvent.backend.flow.domain.FlowJobStatus;
import com.aiadvent.backend.flow.domain.FlowSession;
import com.aiadvent.backend.flow.domain.FlowSessionStatus;
import com.aiadvent.backend.flow.domain.FlowSessionStatus;
import com.aiadvent.backend.flow.domain.FlowStepExecution;
import com.aiadvent.backend.flow.domain.FlowStepStatus;
import com.aiadvent.backend.flow.job.FlowJobPayload;
import com.aiadvent.backend.flow.job.JobQueuePort;
import com.aiadvent.backend.flow.memory.FlowMemoryService;
import com.aiadvent.backend.flow.persistence.AgentVersionRepository;
import com.aiadvent.backend.flow.persistence.FlowEventRepository;
import com.aiadvent.backend.flow.persistence.FlowSessionRepository;
import com.aiadvent.backend.flow.persistence.FlowStepExecutionRepository;
import com.aiadvent.backend.flow.telemetry.FlowTelemetryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class AgentOrchestratorServiceTest {

  private static final String STEP_ID = "step-1";

  @Mock private FlowDefinitionService flowDefinitionService;
  @Mock private FlowDefinitionParser flowDefinitionParser;
  @Mock private FlowSessionRepository flowSessionRepository;
  @Mock private FlowStepExecutionRepository flowStepExecutionRepository;
  @Mock private FlowEventRepository flowEventRepository;
  @Mock private AgentVersionRepository agentVersionRepository;
  @Mock private AgentInvocationService agentInvocationService;
  @Mock private FlowMemoryService flowMemoryService;
  @Mock private JobQueuePort jobQueuePort;
  @Mock private FlowTelemetryService telemetry;

  private AgentOrchestratorService orchestratorService;
  private ObjectMapper objectMapper;

  private FlowDefinition definition;
  private FlowDefinitionDocument document;
  private AgentVersion agentVersion;

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);
    objectMapper = new ObjectMapper();

    orchestratorService =
        new AgentOrchestratorService(
            flowDefinitionService,
            flowDefinitionParser,
            flowSessionRepository,
            flowStepExecutionRepository,
            flowEventRepository,
            agentVersionRepository,
            agentInvocationService,
            flowMemoryService,
            jobQueuePort,
            objectMapper,
            telemetry);

    definition =
        new FlowDefinition(
            "flow",
            1,
            FlowDefinitionStatus.PUBLISHED,
            true,
            objectMapper.createObjectNode());
    setField(definition, "id", UUID.randomUUID());

    agentVersion =
        new AgentVersion(
            new AgentDefinition("agent", "display", null, true),
            1,
            AgentVersionStatus.PUBLISHED,
            com.aiadvent.backend.chat.config.ChatProviderType.OPENAI,
            "openai",
            "gpt-4o-mini");
    setField(agentVersion, "id", UUID.randomUUID());

    FlowStepConfig stepConfig =
            new FlowStepConfig(
            STEP_ID,
            "First step",
            agentVersion.getId(),
            "solve task",
            null,
            List.of(),
            List.of(),
            FlowStepTransitions.defaults(),
            1);
    document = new FlowDefinitionDocument(STEP_ID, Map.of(STEP_ID, stepConfig));

    doAnswer(invocation -> {
          FlowStepExecution execution = invocation.getArgument(0);
          if (execution.getId() == null) {
            setField(execution, "id", UUID.randomUUID());
          }
          return execution;
        })
        .when(flowStepExecutionRepository)
        .save(any(FlowStepExecution.class));

    doAnswer(invocation -> {
          FlowSession entity = invocation.getArgument(0);
          if (entity.getId() == null) {
            setField(entity, "id", UUID.randomUUID());
          }
          return entity;
        })
        .when(flowSessionRepository)
        .save(any(FlowSession.class));
  }

  @Test
  void startCreatesSessionAndEnqueuesJob() {
    when(flowDefinitionService.getActivePublishedDefinition(definition.getId())).thenReturn(definition);
    when(flowDefinitionParser.parse(definition)).thenReturn(document);
    when(agentVersionRepository.findById(agentVersion.getId())).thenReturn(Optional.of(agentVersion));

    FlowSession session =
        orchestratorService.start(definition.getId(), objectMapper.nullNode(), objectMapper.nullNode());

    assertThat(session.getStatus()).isEqualTo(FlowSessionStatus.RUNNING);
    verify(flowEventRepository).save(org.mockito.ArgumentMatchers.any(FlowEvent.class));
    verify(jobQueuePort)
        .enqueueStepJob(eq(session), any(FlowStepExecution.class), any(FlowJobPayload.class), any(Instant.class));
    verify(telemetry).sessionStarted(eq(session.getId()), eq(definition.getId()), eq(definition.getVersion()));
  }

  @Test
  void processNextJobCompletesStepAndRecordsTelemetry() {
    FlowSession session =
        new FlowSession(definition, definition.getVersion(), FlowSessionStatus.RUNNING, 1L, 0L);
    setField(session, "id", UUID.randomUUID());
    FlowStepExecution stepExecution =
        new FlowStepExecution(session, STEP_ID, FlowStepStatus.PENDING, 1);
    setField(stepExecution, "id", UUID.randomUUID());
    FlowJob job = buildJob(session, stepExecution, FlowJobStatus.RUNNING);

    when(jobQueuePort.lockNextPending(eq("worker"), any(Instant.class))).thenReturn(Optional.of(job));
    when(flowStepExecutionRepository.findById(stepExecution.getId())).thenReturn(Optional.of(stepExecution));
    when(flowDefinitionParser.parse(definition)).thenReturn(document);
    when(agentVersionRepository.findById(agentVersion.getId())).thenReturn(Optional.of(agentVersion));
    when(agentInvocationService.invoke(any())).thenReturn(new AgentInvocationResult("answer", UsageCostEstimate.empty(), List.of()));

    FlowSession persistedSession =
        new FlowSession(definition, definition.getVersion(), FlowSessionStatus.RUNNING, 1L, 0L);
    setField(persistedSession, "id", session.getId());
    when(flowSessionRepository.findById(session.getId())).thenReturn(Optional.of(persistedSession));

    orchestratorService.processNextJob("worker");

    assertThat(stepExecution.getStatus()).isEqualTo(FlowStepStatus.COMPLETED);
    ArgumentCaptor<FlowJob> jobCaptor = ArgumentCaptor.forClass(FlowJob.class);
    verify(jobQueuePort).save(jobCaptor.capture());
    assertThat(jobCaptor.getValue().getStatus()).isEqualTo(FlowJobStatus.COMPLETED);
    verify(telemetry)
        .stepCompleted(eq(session.getId()), eq(stepExecution.getId()), eq(STEP_ID), eq(1), any(), any());
    verify(telemetry).sessionCompleted(eq(session.getId()), eq(FlowSessionStatus.COMPLETED), any());
  }

  @Test
  void processNextJobFailureSchedulesRetryAndMarksSessionFailedWhenAttemptsExhausted() {
    FlowSession session =
        new FlowSession(definition, definition.getVersion(), FlowSessionStatus.RUNNING, 1L, 0L);
    setField(session, "id", UUID.randomUUID());
    FlowStepExecution stepExecution =
        new FlowStepExecution(session, STEP_ID, FlowStepStatus.PENDING, 1);
    setField(stepExecution, "id", UUID.randomUUID());
    FlowJob job = buildJob(session, stepExecution, FlowJobStatus.RUNNING);

    FlowStepConfig retryConfig =
        new FlowStepConfig(
            STEP_ID,
            "Retry step",
            agentVersion.getId(),
            "prompt",
            null,
            List.of(),
            List.of(),
            new FlowStepTransitions(null, false, null, true),
            1);
    FlowDefinitionDocument retryDocument =
        new FlowDefinitionDocument(STEP_ID, Map.of(STEP_ID, retryConfig));

    when(jobQueuePort.lockNextPending(eq("worker"), any(Instant.class))).thenReturn(Optional.of(job));
    when(flowStepExecutionRepository.findById(stepExecution.getId())).thenReturn(Optional.of(stepExecution));
    when(flowDefinitionParser.parse(definition)).thenReturn(retryDocument);
    when(agentVersionRepository.findById(agentVersion.getId())).thenReturn(Optional.of(agentVersion));
    when(agentInvocationService.invoke(any())).thenThrow(new RuntimeException("boom"));

    FlowSession persistedSession =
        new FlowSession(definition, definition.getVersion(), FlowSessionStatus.RUNNING, 1L, 0L);
    setField(persistedSession, "id", session.getId());
    when(flowSessionRepository.findById(session.getId())).thenReturn(Optional.of(persistedSession));

    orchestratorService.processNextJob("worker");

    assertThat(stepExecution.getStatus()).isEqualTo(FlowStepStatus.FAILED);
    verify(telemetry)
        .stepFailed(eq(session.getId()), eq(stepExecution.getId()), eq(STEP_ID), eq(1), any(), any());
    verify(telemetry).sessionCompleted(eq(session.getId()), eq(FlowSessionStatus.FAILED), any());
  }

  @Test
  void processNextJobFailureSchedulesRetryWhenAttemptsRemain() {
    FlowSession session =
        new FlowSession(definition, definition.getVersion(), FlowSessionStatus.RUNNING, 1L, 0L);
    setField(session, "id", UUID.randomUUID());
    FlowStepExecution stepExecution =
        new FlowStepExecution(session, STEP_ID, FlowStepStatus.PENDING, 1);
    setField(stepExecution, "id", UUID.randomUUID());
    FlowJob job = buildJob(session, stepExecution, FlowJobStatus.RUNNING);

    FlowStepConfig retryConfig =
        new FlowStepConfig(
            STEP_ID,
            "Retry step",
            agentVersion.getId(),
            "prompt",
            null,
            List.of(),
            List.of(),
            FlowStepTransitions.defaults(),
            2);
    FlowDefinitionDocument retryDocument =
        new FlowDefinitionDocument(STEP_ID, Map.of(STEP_ID, retryConfig));

    when(jobQueuePort.lockNextPending(eq("worker"), any(Instant.class))).thenReturn(Optional.of(job));
    when(flowStepExecutionRepository.findById(stepExecution.getId())).thenReturn(Optional.of(stepExecution));
    when(flowDefinitionParser.parse(definition)).thenReturn(retryDocument);
    when(agentVersionRepository.findById(agentVersion.getId())).thenReturn(Optional.of(agentVersion));
    when(agentInvocationService.invoke(any())).thenThrow(new RuntimeException("boom"));

    FlowSession persistedSession =
        new FlowSession(definition, definition.getVersion(), FlowSessionStatus.RUNNING, 1L, 0L);
    setField(persistedSession, "id", session.getId());
    when(flowSessionRepository.findById(session.getId())).thenReturn(Optional.of(persistedSession));

    orchestratorService.processNextJob("worker");

    ArgumentCaptor<FlowJobPayload> payloadCaptor =
        ArgumentCaptor.forClass(FlowJobPayload.class);
    verify(jobQueuePort).enqueueStepJob(eq(session), any(FlowStepExecution.class), payloadCaptor.capture(), any(Instant.class));
    FlowJobPayload retryPayload = payloadCaptor.getValue();
    assertThat(retryPayload.attempt()).isEqualTo(2);

    verify(telemetry).retryScheduled(eq(session.getId()), eq(STEP_ID), eq(2));
    verify(telemetry, never()).sessionCompleted(eq(session.getId()), eq(FlowSessionStatus.FAILED), any());
  }

  private FlowJob buildJob(
      FlowSession session, FlowStepExecution stepExecution, FlowJobStatus status) {
    FlowJobPayload payload =
        new FlowJobPayload(session.getId(), stepExecution.getId(), stepExecution.getStepId(), stepExecution.getAttempt());
    ObjectNode node = objectMapper.valueToTree(payload);
    FlowJob job = new FlowJob(node, status);
    job.setFlowSession(session);
    job.setFlowStepExecution(stepExecution);
    return job;
  }

  private static void setField(Object target, String fieldName, Object value) {
    try {
      java.lang.reflect.Field field = target.getClass().getDeclaredField(fieldName);
      field.setAccessible(true);
      field.set(target, value);
    } catch (NoSuchFieldException | IllegalAccessException exception) {
      throw new RuntimeException(exception);
    }
  }
}
