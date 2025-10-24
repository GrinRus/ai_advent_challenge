package com.aiadvent.backend.flow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aiadvent.backend.flow.config.FlowDefinitionParser;
import com.aiadvent.backend.flow.config.FlowDefinitionDocument;
import com.aiadvent.backend.flow.config.FlowStepConfig;
import com.aiadvent.backend.flow.config.FlowStepTransitions;
import com.aiadvent.backend.flow.domain.AgentDefinition;
import com.aiadvent.backend.flow.domain.AgentVersion;
import com.aiadvent.backend.flow.domain.AgentVersionStatus;
import com.aiadvent.backend.flow.domain.FlowDefinition;
import com.aiadvent.backend.flow.domain.FlowDefinitionStatus;
import com.aiadvent.backend.flow.domain.FlowEvent;
import com.aiadvent.backend.flow.domain.FlowEventType;
import com.aiadvent.backend.flow.domain.FlowSession;
import com.aiadvent.backend.flow.domain.FlowSessionStatus;
import com.aiadvent.backend.flow.domain.FlowStepExecution;
import com.aiadvent.backend.flow.domain.FlowStepStatus;
import com.aiadvent.backend.flow.domain.FlowInteractionResponseSource;
import com.aiadvent.backend.flow.job.FlowJobPayload;
import com.aiadvent.backend.flow.job.JobQueuePort;
import com.aiadvent.backend.flow.persistence.AgentVersionRepository;
import com.aiadvent.backend.flow.persistence.FlowEventRepository;
import com.aiadvent.backend.flow.persistence.FlowSessionRepository;
import com.aiadvent.backend.flow.persistence.FlowStepExecutionRepository;
import com.aiadvent.backend.flow.telemetry.FlowTelemetryService;
import com.fasterxml.jackson.databind.ObjectMapper;
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

class FlowControlServiceTest {

  @Mock private FlowSessionRepository flowSessionRepository;
  @Mock private FlowStepExecutionRepository flowStepExecutionRepository;
  @Mock private FlowEventRepository flowEventRepository;
  @Mock private FlowDefinitionParser flowDefinitionParser;
  @Mock private AgentVersionRepository agentVersionRepository;
  @Mock private JobQueuePort jobQueuePort;
  @Mock private FlowTelemetryService telemetryService;
  @Mock private FlowInteractionService flowInteractionService;

  private FlowControlService flowControlService;
  private FlowSession session;

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);
    flowControlService =
        new FlowControlService(
            flowSessionRepository,
            flowStepExecutionRepository,
            flowEventRepository,
            flowDefinitionParser,
            agentVersionRepository,
            jobQueuePort,
            new ObjectMapper(),
            telemetryService,
            flowInteractionService);

    FlowDefinition definition =
        new FlowDefinition(
            "test", 1, FlowDefinitionStatus.PUBLISHED, true, new ObjectMapper().createObjectNode());
    session =
        new FlowSession(
            definition,
            1,
            FlowSessionStatus.RUNNING,
            1L,
            0L);
    session.setStartedAt(Instant.now());
    setField(session, "id", UUID.randomUUID());

    when(flowStepExecutionRepository.save(any(FlowStepExecution.class)))
        .thenAnswer(
            invocation -> {
              FlowStepExecution execution = invocation.getArgument(0);
              if (execution.getId() == null) {
                setField(execution, "id", UUID.randomUUID());
              }
              return execution;
            });
  }

  @Test
  void pauseUpdatesStatusAndLogsEvent() {
    UUID sessionId = UUID.randomUUID();
    when(flowSessionRepository.findById(sessionId)).thenReturn(Optional.of(session));

    FlowSession result = flowControlService.pause(sessionId, "manual");

    assertThat(result.getStatus()).isEqualTo(FlowSessionStatus.PAUSED);
    ArgumentCaptor<FlowEvent> captor = ArgumentCaptor.forClass(FlowEvent.class);
    verify(flowEventRepository).save(captor.capture());
    assertThat(captor.getValue().getEventType()).isEqualTo(FlowEventType.FLOW_PAUSED);
  }

  @Test
  void cancelCompletesSessionAndEmitsTelemetry() {
    UUID sessionId = UUID.randomUUID();
    when(flowSessionRepository.findById(sessionId)).thenReturn(Optional.of(session));

    FlowSession result = flowControlService.cancel(sessionId, "manual");

    assertThat(result.getStatus()).isEqualTo(FlowSessionStatus.CANCELLED);
    verify(telemetryService)
        .sessionCompleted(eq(sessionId), eq(FlowSessionStatus.CANCELLED), any());
    verify(flowInteractionService)
        .autoResolvePendingRequests(eq(session), eq(FlowInteractionResponseSource.SYSTEM), eq(null), eq(false));
  }

  @Test
  void resumeKeepsWaitingUserInputState() {
    UUID sessionId = UUID.randomUUID();
    session.setStatus(FlowSessionStatus.WAITING_USER_INPUT);
    when(flowSessionRepository.findById(sessionId)).thenReturn(Optional.of(session));

    FlowSession result = flowControlService.resume(sessionId);

    assertThat(result.getStatus()).isEqualTo(FlowSessionStatus.WAITING_USER_INPUT);
    verify(flowEventRepository, never()).save(any());
  }

  @Test
  void approveStepSchedulesRetry() {
    UUID sessionId = session.getId();
    session.setStatus(FlowSessionStatus.WAITING_STEP_APPROVAL);

    FlowStepExecution execution =
        new FlowStepExecution(session, "step-approve", FlowStepStatus.WAITING_APPROVAL, 1);
    setField(execution, "id", UUID.randomUUID());
    execution.setInputPayload(new ObjectMapper().createObjectNode());

    FlowStepConfig config =
        new FlowStepConfig(
            "step-approve",
            "Review",
            UUID.randomUUID(),
            "prompt",
            null,
            null,
            List.of(),
            List.of(),
            FlowStepTransitions.defaults(),
            1);
    FlowDefinitionDocument document =
        new FlowDefinitionDocument("step-approve", Map.of("step-approve", config));

    AgentVersion agentVersion = createAgentVersion(config.agentVersionId());

    when(flowSessionRepository.findById(sessionId)).thenReturn(Optional.of(session));
    when(flowStepExecutionRepository.findById(execution.getId())).thenReturn(Optional.of(execution));
    when(flowDefinitionParser.parse(session.getFlowDefinition())).thenReturn(document);
    when(agentVersionRepository.findById(config.agentVersionId())).thenReturn(Optional.of(agentVersion));

    FlowSession result = flowControlService.approveStep(sessionId, execution.getId());

    assertThat(result.getStatus()).isEqualTo(FlowSessionStatus.RUNNING);
    assertThat(execution.getStatus()).isEqualTo(FlowStepStatus.FAILED);
    verify(jobQueuePort)
        .enqueueStepJob(eq(session), any(FlowStepExecution.class), any(FlowJobPayload.class), any(Instant.class));
  }

  @Test
  void skipStepSchedulesFallback() {
    UUID sessionId = session.getId();
    session.setStatus(FlowSessionStatus.WAITING_STEP_APPROVAL);

    FlowStepExecution execution =
        new FlowStepExecution(session, "primary", FlowStepStatus.WAITING_APPROVAL, 1);
    setField(execution, "id", UUID.randomUUID());

    UUID agentVersionId = UUID.randomUUID();
    FlowStepConfig primaryConfig =
        new FlowStepConfig(
            "primary",
            "Primary",
            agentVersionId,
            "prompt",
            null,
            null,
            List.of(),
            List.of(),
            new FlowStepTransitions(null, true, "fallback", false),
            1);
    FlowStepConfig fallbackConfig =
        new FlowStepConfig(
            "fallback",
            "Fallback",
            agentVersionId,
            "cleanup",
            null,
            null,
            List.of(),
            List.of(),
            FlowStepTransitions.defaults(),
            1);
    FlowDefinitionDocument document =
        new FlowDefinitionDocument("primary", Map.of("primary", primaryConfig, "fallback", fallbackConfig));

    AgentVersion agentVersion = createAgentVersion(agentVersionId);

    when(flowSessionRepository.findById(sessionId)).thenReturn(Optional.of(session));
    when(flowStepExecutionRepository.findById(execution.getId())).thenReturn(Optional.of(execution));
    when(flowDefinitionParser.parse(session.getFlowDefinition())).thenReturn(document);
    when(agentVersionRepository.findById(agentVersionId)).thenReturn(Optional.of(agentVersion));

    FlowSession result = flowControlService.skipStep(sessionId, execution.getId());

    assertThat(result.getStatus()).isEqualTo(FlowSessionStatus.RUNNING);
    assertThat(session.getCurrentStepId()).isEqualTo("fallback");
    assertThat(execution.getStatus()).isEqualTo(FlowStepStatus.SKIPPED);
    verify(jobQueuePort)
        .enqueueStepJob(eq(session), any(FlowStepExecution.class), any(FlowJobPayload.class), any(Instant.class));
  }

  private AgentVersion createAgentVersion(UUID id) {
    AgentDefinition definition = new AgentDefinition("agent", "display", null, true);
    AgentVersion version =
        new AgentVersion(
            definition,
            1,
            AgentVersionStatus.PUBLISHED,
            com.aiadvent.backend.chat.config.ChatProviderType.OPENAI,
            "openai",
            "gpt-4o-mini");
    setField(version, "id", id);
    return version;
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
