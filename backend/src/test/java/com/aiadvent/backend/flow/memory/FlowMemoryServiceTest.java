package com.aiadvent.backend.flow.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.aiadvent.backend.flow.domain.FlowDefinition;
import com.aiadvent.backend.flow.domain.FlowDefinitionStatus;
import com.aiadvent.backend.flow.domain.FlowMemoryVersion;
import com.aiadvent.backend.flow.domain.FlowSession;
import com.aiadvent.backend.flow.domain.FlowSessionStatus;
import com.aiadvent.backend.flow.persistence.FlowMemoryVersionRepository;
import com.aiadvent.backend.flow.persistence.FlowSessionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

class FlowMemoryServiceTest {

  @Mock private FlowSessionRepository flowSessionRepository;
  @Mock private FlowMemoryVersionRepository flowMemoryVersionRepository;

  private FlowMemoryService flowMemoryService;
  private FlowSession session;
  private final ObjectMapper objectMapper = new ObjectMapper();

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);
    flowMemoryService = new FlowMemoryService(flowSessionRepository, flowMemoryVersionRepository, objectMapper);

    FlowDefinition definition =
        new FlowDefinition("memory", 1, FlowDefinitionStatus.PUBLISHED, true, objectMapper.createObjectNode());
    session = new FlowSession(definition, 1, FlowSessionStatus.RUNNING, 0L, 0L);
    setField(session, "id", UUID.randomUUID());
  }

  @Test
  void appendEnforcesRetentionPolicy() {
    UUID sessionId = session.getId();
    String channel = "shared";

    FlowMemoryVersion latest = new FlowMemoryVersion(session, channel, 12L, objectMapper.createObjectNode(), null);
    setField(latest, "id", 42L);

    when(flowSessionRepository.findByIdForUpdate(sessionId)).thenReturn(Optional.of(session));
    when(flowMemoryVersionRepository.findFirstByFlowSessionAndChannelOrderByVersionDesc(session, channel))
        .thenReturn(Optional.of(latest));
    when(flowMemoryVersionRepository.save(Mockito.any())).thenAnswer(invocation -> invocation.getArgument(0));

    FlowMemoryVersion saved = flowMemoryService.append(sessionId, channel, objectMapper.createObjectNode(), null);

    assertThat(saved.getVersion()).isEqualTo(13L);
    Mockito.verify(flowMemoryVersionRepository)
        .deleteByFlowSessionAndChannelAndVersionLessThan(session, channel, 4L);

    ArgumentCaptor<Instant> cutoffCaptor = ArgumentCaptor.forClass(Instant.class);
    Mockito.verify(flowMemoryVersionRepository)
        .deleteByFlowSessionAndChannelAndCreatedAtBefore(Mockito.eq(session), Mockito.eq(channel), cutoffCaptor.capture());

    Instant cutoff = cutoffCaptor.getValue();
    assertThat(cutoff).isBefore(Instant.now());
    assertThat(cutoff).isAfter(Instant.now().minusSeconds(60L * 60 * 24 * 31));
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
