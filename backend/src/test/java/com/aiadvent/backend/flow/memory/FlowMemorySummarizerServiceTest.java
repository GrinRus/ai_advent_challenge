package com.aiadvent.backend.flow.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aiadvent.backend.chat.config.ChatMemoryProperties;
import com.aiadvent.backend.chat.config.ChatProvidersProperties;
import com.aiadvent.backend.chat.memory.ChatMemorySummarizerService;
import com.aiadvent.backend.chat.memory.ChatMemorySummarizerService.SummarizationDecision;
import com.aiadvent.backend.chat.provider.ChatProviderService;
import com.aiadvent.backend.chat.token.TokenUsageEstimator;
import com.aiadvent.backend.chat.token.TokenUsageEstimator.Estimate;
import com.aiadvent.backend.flow.domain.FlowDefinition;
import com.aiadvent.backend.flow.domain.FlowDefinitionStatus;
import com.aiadvent.backend.flow.domain.FlowMemoryVersion;
import com.aiadvent.backend.flow.domain.FlowSession;
import com.aiadvent.backend.flow.domain.FlowSessionStatus;
import com.aiadvent.backend.flow.memory.FlowMemorySourceType;
import com.aiadvent.backend.flow.persistence.AgentVersionRepository;
import com.aiadvent.backend.flow.persistence.FlowMemorySummaryRepository;
import com.aiadvent.backend.flow.persistence.FlowMemoryVersionRepository;
import com.aiadvent.backend.flow.persistence.FlowSessionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

class FlowMemorySummarizerServiceTest {

  @Mock private FlowSessionRepository flowSessionRepository;
  @Mock private FlowMemoryVersionRepository flowMemoryVersionRepository;
  @Mock private FlowMemorySummaryRepository flowMemorySummaryRepository;
  @Mock private AgentVersionRepository agentVersionRepository;
  @Mock private ChatMemorySummarizerService chatMemorySummarizerService;
  @Mock private TokenUsageEstimator tokenUsageEstimator;
  @Mock private ChatProviderService chatProviderService;

  private SimpleMeterRegistry meterRegistry;
  private FlowMemorySummarizerService flowMemorySummarizerService;
  private FlowSession session;
  private final ObjectMapper objectMapper = new ObjectMapper();

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);
    ChatMemoryProperties properties = new ChatMemoryProperties();
    properties.getSummarization().setEnabled(true);
    properties.setWindowSize(4);
    meterRegistry = new SimpleMeterRegistry();
    flowMemorySummarizerService =
        new FlowMemorySummarizerService(
            flowSessionRepository,
            flowMemoryVersionRepository,
            flowMemorySummaryRepository,
            agentVersionRepository,
            chatMemorySummarizerService,
            properties,
            tokenUsageEstimator,
            objectMapper,
            chatProviderService,
            meterRegistry);

    FlowDefinition definition =
        new FlowDefinition("def", 1, FlowDefinitionStatus.PUBLISHED, true, objectMapper.createObjectNode());
    session = new FlowSession(definition, 1, FlowSessionStatus.RUNNING, 0L, 0L);
    setField(session, "id", UUID.randomUUID());
  }

  @AfterEach
  void tearDown() {
    if (meterRegistry != null) {
      meterRegistry.close();
    }
  }

  @Test
  void preflightReturnsPlanWhenThresholdExceeded() {
    UUID sessionId = session.getId();
    when(chatMemorySummarizerService.isEnabled()).thenReturn(true);
    when(chatMemorySummarizerService.triggerTokenLimit()).thenReturn(4000);
    when(chatMemorySummarizerService.targetTokenCount()).thenReturn(2000);

    when(flowSessionRepository.findById(sessionId)).thenReturn(Optional.of(session));
    when(flowMemorySummaryRepository.findFirstByFlowSessionAndChannelOrderBySourceVersionEndDesc(session, "conversation"))
        .thenReturn(Optional.empty());
    when(flowMemoryVersionRepository.findByFlowSessionAndChannelOrderByVersionAsc(session, "conversation"))
        .thenReturn(sampleVersions(session));

    ChatProvidersProperties.Model model = new ChatProvidersProperties.Model();
    ChatProvidersProperties.Usage usage = new ChatProvidersProperties.Usage();
    usage.setFallbackTokenizer("cl100k_base");
    model.setUsage(usage);
    when(chatProviderService.model("openai", "gpt-4o-mini")).thenReturn(model);

    when(tokenUsageEstimator.estimate(any()))
        .thenReturn(new Estimate(5000, 0, 5000, false, false));

    var result =
        flowMemorySummarizerService.preflight(sessionId, "conversation", "openai", "gpt-4o-mini", "next user prompt");

    assertThat(result).isPresent();
    assertThat(result.get().entries()).hasSize(4);
    assertThat(result.get().decision().estimatedTokens()).isEqualTo(5000);
  }

  @Test
  void processPersistsSummary() {
    UUID sessionId = session.getId();
    when(chatMemorySummarizerService.isEnabled()).thenReturn(true);
    when(chatMemorySummarizerService.triggerTokenLimit()).thenReturn(4000);
    when(chatMemorySummarizerService.targetTokenCount()).thenReturn(2000);
    when(chatMemorySummarizerService.tryAcquireSlot()).thenReturn(true);
    when(chatMemorySummarizerService.summarizeTranscript(any(), anyString(), eq("flow")))
        .thenReturn(Optional.of("Summarised flow context"));

    when(flowSessionRepository.findById(sessionId)).thenReturn(Optional.of(session));
    when(flowSessionRepository.findByIdForUpdate(sessionId)).thenReturn(Optional.of(session));
    when(flowMemorySummaryRepository.findFirstByFlowSessionAndChannelOrderBySourceVersionEndDesc(session, "conversation"))
        .thenReturn(Optional.empty());
    List<FlowMemoryVersion> versions = sampleVersions(session);
    UUID agentVersionId = UUID.randomUUID();
    versions.forEach(version -> version.setAgentVersionId(agentVersionId));
    var agentVersion = Mockito.mock(com.aiadvent.backend.flow.domain.AgentVersion.class);
    when(agentVersionRepository.findById(agentVersionId)).thenReturn(Optional.of(agentVersion));
    when(flowMemoryVersionRepository.findByFlowSessionAndChannelOrderByVersionAsc(session, "conversation"))
        .thenReturn(versions);

    ChatProvidersProperties.Model model = new ChatProvidersProperties.Model();
    ChatProvidersProperties.Usage usage = new ChatProvidersProperties.Usage();
    usage.setFallbackTokenizer("cl100k_base");
    model.setUsage(usage);
    when(chatProviderService.model("openai", "gpt-4o-mini")).thenReturn(model);

    when(tokenUsageEstimator.estimate(any()))
        .thenReturn(new Estimate(5000, 0, 5000, false, false));

    var preflight =
        flowMemorySummarizerService.preflight(sessionId, "conversation", "openai", "gpt-4o-mini", null);
    assertThat(preflight).isPresent();

    flowMemorySummarizerService.processPreflightResult(preflight.get());

    ArgumentCaptor<com.aiadvent.backend.flow.domain.FlowMemorySummary> summaryCaptor =
        ArgumentCaptor.forClass(com.aiadvent.backend.flow.domain.FlowMemorySummary.class);
    verify(flowMemorySummaryRepository).save(summaryCaptor.capture());
    com.aiadvent.backend.flow.domain.FlowMemorySummary saved = summaryCaptor.getValue();
    assertThat(saved.getSummaryText()).contains("Summarised flow context");
    assertThat(saved.getSourceVersionStart()).isEqualTo(1L);
    assertThat(saved.getSourceVersionEnd()).isEqualTo(4L);
    assertThat(saved.getAgentVersion()).isEqualTo(agentVersion);
    assertThat(saved.getLanguage()).isEqualTo("en");
    assertThat(saved.getMetadata()).isNotNull();
    assertThat(saved.getMetadata().get("schemaVersion").asInt()).isEqualTo(1);
    assertThat(saved.getMetadata().get("summary").asBoolean()).isTrue();
    assertThat(meterRegistry.find("flow_summary_runs_total").counter().count()).isEqualTo(1d);
  }

  @Test
  void forceSummarizeDeletesExistingSummariesAndRunsPlan() {
    UUID sessionId = session.getId();
    when(chatMemorySummarizerService.tryAcquireSlot()).thenReturn(true);
    when(chatMemorySummarizerService.summarizeTranscript(any(), anyString(), eq("flow")))
        .thenReturn(Optional.of("Manual flow summary"));
    when(chatMemorySummarizerService.triggerTokenLimit()).thenReturn(4000);
    when(chatMemorySummarizerService.targetTokenCount()).thenReturn(2000);

    when(flowSessionRepository.findById(sessionId)).thenReturn(Optional.of(session));
    when(flowSessionRepository.findByIdForUpdate(sessionId)).thenReturn(Optional.of(session));
    when(flowMemorySummaryRepository.findFirstByFlowSessionAndChannelOrderBySourceVersionEndDesc(session, "conversation"))
        .thenReturn(Optional.empty());
    List<FlowMemoryVersion> versions = sampleVersions(session);
    UUID agentVersionId = UUID.randomUUID();
    versions.forEach(version -> version.setAgentVersionId(agentVersionId));
    var agentVersion = Mockito.mock(com.aiadvent.backend.flow.domain.AgentVersion.class);
    when(agentVersionRepository.findById(agentVersionId)).thenReturn(Optional.of(agentVersion));
    when(flowMemoryVersionRepository.findByFlowSessionAndChannelOrderByVersionAsc(session, "conversation"))
        .thenReturn(versions);

    ChatProvidersProperties.Model model = new ChatProvidersProperties.Model();
    ChatProvidersProperties.Usage usage = new ChatProvidersProperties.Usage();
    usage.setFallbackTokenizer("cl100k_base");
    model.setUsage(usage);
    when(chatProviderService.model("openai", "gpt-4o-mini")).thenReturn(model);
    when(tokenUsageEstimator.estimate(any())).thenReturn(new Estimate(2000, 0, 2000, false, false));

    flowMemorySummarizerService.forceSummarize(sessionId, "openai", "gpt-4o-mini", List.of("conversation"));

    verify(flowMemorySummaryRepository)
        .deleteByFlowSessionAndChannel(session, "conversation");
    verify(flowMemorySummaryRepository, atLeastOnce()).save(any());
  }

  @Test
  void processPreflightResultRecordsQueueRejectionsWhenWorkersBusy() {
    UUID sessionId = session.getId();
    when(chatMemorySummarizerService.isEnabled()).thenReturn(true);
    when(chatMemorySummarizerService.triggerTokenLimit()).thenReturn(1000);
    when(chatMemorySummarizerService.targetTokenCount()).thenReturn(500);
    when(chatMemorySummarizerService.tryAcquireSlot()).thenReturn(false);

    when(flowSessionRepository.findById(sessionId)).thenReturn(Optional.of(session));
    when(flowMemorySummaryRepository.findFirstByFlowSessionAndChannelOrderBySourceVersionEndDesc(session, "conversation"))
        .thenReturn(Optional.empty());
    when(flowMemoryVersionRepository.findByFlowSessionAndChannelOrderByVersionAsc(session, "conversation"))
        .thenReturn(sampleVersions(session));

    ChatProvidersProperties.Model model = new ChatProvidersProperties.Model();
    ChatProvidersProperties.Usage usage = new ChatProvidersProperties.Usage();
    usage.setFallbackTokenizer("cl100k_base");
    model.setUsage(usage);
    when(chatProviderService.model("openai", "gpt-4o-mini")).thenReturn(model);
    when(tokenUsageEstimator.estimate(any())).thenReturn(new Estimate(2000, 0, 2000, false, false));

    var preflight =
        flowMemorySummarizerService.preflight(sessionId, "conversation", "openai", "gpt-4o-mini", null);
    assertThat(preflight).isPresent();

    flowMemorySummarizerService.processPreflightResult(preflight.get());

    assertThat(meterRegistry.find("flow_summary_queue_rejections_total").counter().count()).isEqualTo(1.0d);
  }

  @Test
  void processPreflightResultTracksFailuresAndAlerts() {
    UUID sessionId = session.getId();
    when(chatMemorySummarizerService.isEnabled()).thenReturn(true);
    when(chatMemorySummarizerService.triggerTokenLimit()).thenReturn(1000);
    when(chatMemorySummarizerService.targetTokenCount()).thenReturn(500);
    when(chatMemorySummarizerService.tryAcquireSlot()).thenReturn(true);
    when(chatMemorySummarizerService.summarizeTranscript(any(), anyString(), eq("flow")))
        .thenThrow(new RuntimeException("summariser down"));

    when(flowSessionRepository.findById(sessionId)).thenReturn(Optional.of(session));
    when(flowSessionRepository.findByIdForUpdate(sessionId)).thenReturn(Optional.of(session));
    when(flowMemorySummaryRepository.findFirstByFlowSessionAndChannelOrderBySourceVersionEndDesc(session, "conversation"))
        .thenReturn(Optional.empty());
    when(flowMemoryVersionRepository.findByFlowSessionAndChannelOrderByVersionAsc(session, "conversation"))
        .thenReturn(sampleVersions(session));

    ChatProvidersProperties.Model model = new ChatProvidersProperties.Model();
    ChatProvidersProperties.Usage usage = new ChatProvidersProperties.Usage();
    usage.setFallbackTokenizer("cl100k_base");
    model.setUsage(usage);
    when(chatProviderService.model("openai", "gpt-4o-mini")).thenReturn(model);
    when(tokenUsageEstimator.estimate(any())).thenReturn(new Estimate(2000, 0, 2000, false, false));

    var preflight =
        flowMemorySummarizerService.preflight(sessionId, "conversation", "openai", "gpt-4o-mini", null);
    assertThat(preflight).isPresent();

    for (int attempt = 0; attempt < 3; attempt++) {
      try {
        flowMemorySummarizerService.processPreflightResult(preflight.get());
      } catch (Exception ignored) {
        // expected because summarizeTranscript throws
      }
    }

    assertThat(meterRegistry.find("flow_summary_failures_total").counter().count()).isEqualTo(3.0d);
    assertThat(meterRegistry.find("flow_summary_failure_alerts_total").counter().count()).isEqualTo(1.0d);
  }

  private List<FlowMemoryVersion> sampleVersions(FlowSession session) {
    List<FlowMemoryVersion> versions = new java.util.ArrayList<>();
    for (int i = 1; i <= 8; i++) {
      ObjectNode payload = objectMapper.createObjectNode();
      payload.put("prompt", "message-" + i);
      FlowMemoryVersion version =
          new FlowMemoryVersion(session, "conversation", i, payload, null);
      version.setSourceType(i % 2 == 0 ? FlowMemorySourceType.AGENT_OUTPUT : FlowMemorySourceType.USER_INPUT);
      version.setStepId("step-" + ((i + 1) / 2));
      version.setStepAttempt((i + 1) / 2);
      versions.add(version);
    }
    return versions;
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
