package com.aiadvent.backend.flow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.aiadvent.backend.flow.api.FlowEventDto;
import com.aiadvent.backend.flow.domain.FlowDefinition;
import com.aiadvent.backend.flow.domain.FlowDefinitionStatus;
import com.aiadvent.backend.flow.domain.FlowEvent;
import com.aiadvent.backend.flow.domain.FlowEventType;
import com.aiadvent.backend.flow.domain.FlowSession;
import com.aiadvent.backend.flow.domain.FlowSessionStatus;
import com.aiadvent.backend.flow.persistence.FlowEventRepository;
import com.aiadvent.backend.flow.persistence.FlowSessionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class FlowStatusServiceTest {

  @Mock private FlowSessionRepository flowSessionRepository;
  @Mock private FlowEventRepository flowEventRepository;
  @Mock private FlowQueryService flowQueryService;

  private FlowStatusService flowStatusService;
  private FlowSession session;
  private UUID sessionId;

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);
    flowStatusService =
        new FlowStatusService(flowSessionRepository, flowEventRepository, flowQueryService);

    FlowDefinition definition =
        new FlowDefinition(
            "test-flow",
            1,
            FlowDefinitionStatus.PUBLISHED,
            true,
            new ObjectMapper().createObjectNode().putArray("steps"));
    session =
        new FlowSession(
            definition,
            1,
            FlowSessionStatus.RUNNING,
            1L,
            0L);
    // mimic persisted state
    session.setStartedAt(Instant.now());
    sessionId = UUID.randomUUID();
    setField(session, "id", sessionId);
  }

  @Test
  void pollSessionReturnsEventsImmediately() {
    FlowEvent event = new FlowEvent(session, FlowEventType.STEP_STARTED, "running", null);
    setField(event, "id", 1L);
    when(flowSessionRepository.findById(sessionId)).thenReturn(Optional.of(session));
    when(flowEventRepository.findByFlowSessionAndIdGreaterThanOrderByIdAsc(session, 0L))
        .thenReturn(List.of(event));

    Optional<FlowStatusService.FlowStatusResponse> response =
        flowStatusService.pollSession(sessionId, null, null, Duration.ofSeconds(1));

    assertThat(response).isPresent();
    FlowStatusService.FlowStatusResponse payload = response.get();
    assertThat(payload.events()).hasSize(1);
    FlowEventDto dto = payload.events().get(0);
    assertThat(dto.type()).isEqualTo(FlowEventType.STEP_STARTED);
    assertThat(payload.state().sessionId()).isEqualTo(sessionId);
  }

  @Test
  void pollSessionReturnsStateChangeWithoutEvents() {
    session.setStateVersion(2L);
    when(flowSessionRepository.findById(sessionId)).thenReturn(Optional.of(session));
    when(flowEventRepository.findByFlowSessionAndIdGreaterThanOrderByIdAsc(session, 0L))
        .thenReturn(List.of());
    when(flowEventRepository.findTopByFlowSessionOrderByIdDesc(session)).thenReturn(null);

    Optional<FlowStatusService.FlowStatusResponse> response =
        flowStatusService.pollSession(sessionId, null, 1L, Duration.ofSeconds(1));

    assertThat(response).isPresent();
    assertThat(response.get().state().stateVersion()).isEqualTo(2L);
  }

  @Test
  void currentSnapshotReturnsAllEvents() {
    FlowEvent event1 = new FlowEvent(session, FlowEventType.FLOW_STARTED, "running", null);
    FlowEvent event2 = new FlowEvent(session, FlowEventType.STEP_STARTED, "running", null);
    setField(event1, "id", 1L);
    setField(event2, "id", 2L);
    when(flowSessionRepository.findById(sessionId)).thenReturn(Optional.of(session));
    when(flowEventRepository.findByFlowSessionOrderByIdAsc(session))
        .thenReturn(List.of(event1, event2));

    FlowStatusService.FlowStatusResponse snapshot = flowStatusService.currentSnapshot(sessionId);

    assertThat(snapshot.events()).hasSize(2);
    assertThat(snapshot.state().sessionId()).isEqualTo(sessionId);
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
