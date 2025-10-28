package com.aiadvent.backend.flow.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.aiadvent.backend.flow.TestFlowBlueprintFactory;
import com.aiadvent.backend.flow.blueprint.FlowBlueprint;
import com.aiadvent.backend.flow.blueprint.FlowBlueprintMemory;
import com.aiadvent.backend.flow.blueprint.FlowBlueprintMemoryChannel;
import com.aiadvent.backend.flow.blueprint.FlowBlueprintMetadata;
import com.aiadvent.backend.flow.blueprint.FlowBlueprintSchemaVersion;
import com.aiadvent.backend.flow.blueprint.FlowBlueprintStep;
import com.aiadvent.backend.flow.blueprint.FlowStepTransitionsDraft;
import com.aiadvent.backend.flow.config.FlowMemoryChannelConfig;
import com.aiadvent.backend.flow.config.FlowMemoryConfig;
import com.aiadvent.backend.flow.domain.FlowDefinition;
import com.aiadvent.backend.flow.domain.FlowDefinitionStatus;
import com.aiadvent.backend.flow.domain.FlowMemorySummary;
import com.aiadvent.backend.flow.domain.FlowMemoryVersion;
import com.aiadvent.backend.flow.domain.FlowSession;
import com.aiadvent.backend.flow.domain.FlowSessionStatus;
import com.aiadvent.backend.flow.persistence.FlowMemorySummaryRepository;
import com.aiadvent.backend.flow.persistence.FlowMemoryVersionRepository;
import com.aiadvent.backend.flow.persistence.FlowSessionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
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
  @Mock private FlowMemorySummaryRepository flowMemorySummaryRepository;

  private FlowMemoryService flowMemoryService;
  private FlowSession session;
  private final ObjectMapper objectMapper = new ObjectMapper();

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);
    flowMemoryService =
        new FlowMemoryService(
            flowSessionRepository, flowMemoryVersionRepository, flowMemorySummaryRepository, objectMapper);

    FlowDefinition definition =
        new FlowDefinition(
            "memory", 1, FlowDefinitionStatus.PUBLISHED, true, TestFlowBlueprintFactory.simpleBlueprint());
    session = new FlowSession(definition, 1, FlowSessionStatus.RUNNING, 0L, 0L);
    setField(session, "id", UUID.randomUUID());
  }

  @Test
  void historyIncludesSummariesAndTail() {
    UUID sessionId = session.getId();
    String channel = "conversation";

    FlowMemoryVersion v5 = new FlowMemoryVersion(session, channel, 5L, objectMapper.createObjectNode(), null);
    FlowMemoryVersion v6 = new FlowMemoryVersion(session, channel, 6L, objectMapper.createObjectNode(), null);

    FlowMemorySummary summary =
        new FlowMemorySummary(session, channel, 1L, 4L, "Earlier summary content");

    when(flowSessionRepository.findById(sessionId)).thenReturn(Optional.of(session));
    when(flowMemorySummaryRepository.findByFlowSessionAndChannelOrderBySourceVersionStart(session, channel))
        .thenReturn(java.util.List.of(summary));
    when(flowMemoryVersionRepository.findByFlowSessionAndChannelAndVersionGreaterThanOrderByVersionAsc(session, channel, 4L))
        .thenReturn(java.util.List.of(v5, v6));

    java.util.List<com.fasterxml.jackson.databind.JsonNode> history =
        flowMemoryService.history(sessionId, channel, 10);

    assertThat(history).hasSize(3);
    assertThat(history.get(0).get("type").asText()).isEqualTo("summary");
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

    FlowMemoryMetadata metadata =
        FlowMemoryMetadata.builder()
            .sourceType(FlowMemorySourceType.AGENT_OUTPUT)
            .stepId("step-1")
            .stepAttempt(1)
            .agentVersionId(UUID.randomUUID())
            .createdByStepId(UUID.randomUUID())
            .build();

    FlowMemoryVersion saved =
        flowMemoryService.append(sessionId, channel, objectMapper.createObjectNode(), metadata);

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

  @Test
  void appendUsesCustomRetentionFromInitialization() {
    UUID sessionId = session.getId();
    String channel = "analytics";

    flowMemoryService.initializeSharedChannels(
        sessionId,
        new FlowMemoryConfig(
            List.of(new FlowMemoryChannelConfig(channel, 2, Duration.ofDays(1)))));

    FlowMemoryVersion latest = new FlowMemoryVersion(session, channel, 6L, objectMapper.createObjectNode(), null);
    setField(latest, "id", 21L);

    when(flowSessionRepository.findByIdForUpdate(sessionId)).thenReturn(Optional.of(session));
    when(flowMemoryVersionRepository.findFirstByFlowSessionAndChannelOrderByVersionDesc(session, channel))
        .thenReturn(Optional.of(latest));
    when(flowMemoryVersionRepository.save(Mockito.any())).thenAnswer(invocation -> invocation.getArgument(0));

    flowMemoryService.append(sessionId, channel, objectMapper.createObjectNode(), FlowMemoryMetadata.builder().build());

    Mockito.verify(flowMemoryVersionRepository)
        .deleteByFlowSessionAndChannelAndVersionLessThan(session, channel, 6L);

    ArgumentCaptor<Instant> cutoffCaptor = ArgumentCaptor.forClass(Instant.class);
    Mockito.verify(flowMemoryVersionRepository)
        .deleteByFlowSessionAndChannelAndCreatedAtBefore(Mockito.eq(session), Mockito.eq(channel), cutoffCaptor.capture());

    Instant cutoff = cutoffCaptor.getValue();
    assertThat(cutoff).isAfter(Instant.now().minus(Duration.ofDays(2)))
        .isBefore(Instant.now());
  }

  @Test
  void appendRespectsBlueprintRetentionWhenPolicyNotInitialized() {
    UUID sessionId = session.getId();
    String channel = "analytics";

    FlowBlueprint blueprint =
        new FlowBlueprint(
            FlowBlueprintSchemaVersion.CURRENT,
            new FlowBlueprintMetadata("Memory-aware flow", null, List.of()),
            null,
            null,
            "step-1",
            true,
            List.of(),
            new FlowBlueprintMemory(List.of(new FlowBlueprintMemoryChannel(channel, 3, 2))),
            List.of(
                new FlowBlueprintStep(
                    "step-1",
                    "Bootstrap",
                    UUID.randomUUID().toString(),
                    "Prompt",
                    null,
                    null,
                    List.of(),
                    List.of(),
                    new FlowStepTransitionsDraft(
                        new FlowStepTransitionsDraft.Success(null, true),
                        null),
                    1)));

    session.getFlowDefinition().setDefinition(blueprint);

    FlowMemoryVersion latest =
        new FlowMemoryVersion(session, channel, 7L, objectMapper.createObjectNode(), null);
    setField(latest, "id", 84L);

    when(flowSessionRepository.findByIdForUpdate(sessionId)).thenReturn(Optional.of(session));
    when(flowMemoryVersionRepository.findFirstByFlowSessionAndChannelOrderByVersionDesc(session, channel))
        .thenReturn(Optional.of(latest));
    when(flowMemoryVersionRepository.save(Mockito.any())).thenAnswer(invocation -> invocation.getArgument(0));

    flowMemoryService.append(
        sessionId, channel, objectMapper.createObjectNode(), FlowMemoryMetadata.builder().build());

    Mockito.verify(flowMemoryVersionRepository)
        .deleteByFlowSessionAndChannelAndVersionLessThan(session, channel, 6L);

    ArgumentCaptor<Instant> cutoffCaptor = ArgumentCaptor.forClass(Instant.class);
    Mockito.verify(flowMemoryVersionRepository)
        .deleteByFlowSessionAndChannelAndCreatedAtBefore(
            Mockito.eq(session), Mockito.eq(channel), cutoffCaptor.capture());

    Instant cutoff = cutoffCaptor.getValue();
    Duration age = Duration.between(cutoff, Instant.now());
    assertThat(age).isGreaterThan(Duration.ofDays(1)).isLessThan(Duration.ofDays(3));
    assertThat(session.getCurrentMemoryVersion()).isEqualTo(8L);
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
