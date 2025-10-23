package com.aiadvent.backend.flow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

import com.aiadvent.backend.flow.config.FlowDefinitionParser;
import com.aiadvent.backend.flow.domain.FlowDefinition;
import com.aiadvent.backend.flow.domain.FlowDefinitionStatus;
import com.aiadvent.backend.flow.domain.FlowEvent;
import com.aiadvent.backend.flow.domain.FlowEventType;
import com.aiadvent.backend.flow.domain.FlowSession;
import com.aiadvent.backend.flow.domain.FlowSessionStatus;
import com.aiadvent.backend.flow.job.JobQueuePort;
import com.aiadvent.backend.flow.persistence.AgentVersionRepository;
import com.aiadvent.backend.flow.persistence.FlowEventRepository;
import com.aiadvent.backend.flow.persistence.FlowSessionRepository;
import com.aiadvent.backend.flow.persistence.FlowStepExecutionRepository;
import com.aiadvent.backend.flow.telemetry.FlowTelemetryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
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
            telemetryService);

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
  }
}
