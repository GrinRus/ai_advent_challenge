package com.aiadvent.backend.flow.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.aiadvent.backend.chat.config.ChatProvidersProperties;
import com.aiadvent.backend.chat.memory.ChatMemorySummarizerService;
import com.aiadvent.backend.chat.provider.ChatProviderService;
import com.aiadvent.backend.chat.token.TokenUsageEstimator;
import com.aiadvent.backend.chat.token.TokenUsageEstimator.Estimate;
import com.aiadvent.backend.flow.domain.FlowDefinition;
import com.aiadvent.backend.flow.domain.FlowDefinitionStatus;
import com.aiadvent.backend.flow.domain.FlowMemorySummary;
import com.aiadvent.backend.flow.domain.FlowMemoryVersion;
import com.aiadvent.backend.flow.domain.FlowSession;
import com.aiadvent.backend.flow.domain.FlowSessionStatus;
import com.aiadvent.backend.flow.persistence.FlowDefinitionRepository;
import com.aiadvent.backend.flow.persistence.FlowMemorySummaryRepository;
import com.aiadvent.backend.flow.persistence.FlowMemoryVersionRepository;
import com.aiadvent.backend.flow.persistence.FlowSessionRepository;
import com.aiadvent.backend.support.PostgresTestContainer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@org.springframework.test.context.TestPropertySource(properties = {
    "app.chat.memory.window-size=4",
    "app.chat.memory.summarization.enabled=true",
    "app.chat.memory.summarization.trigger-token-limit=0",
    "app.chat.memory.summarization.target-token-count=40"
})
@Import({
  FlowMemoryService.class,
  FlowMemorySummarizerService.class,
  FlowMemorySummarizerIntegrationTest.TestConfig.class
})
class FlowMemorySummarizerIntegrationTest extends PostgresTestContainer {

  @Autowired private FlowMemoryService flowMemoryService;
  @Autowired private FlowMemorySummarizerService flowMemorySummarizerService;
  @Autowired private FlowDefinitionRepository flowDefinitionRepository;
  @Autowired private FlowSessionRepository flowSessionRepository;
  @Autowired private FlowMemorySummaryRepository summaryRepository;
  @Autowired private FlowMemoryVersionRepository flowMemoryVersionRepository;
  @Autowired private ObjectMapper objectMapper;

  @MockBean private ChatMemorySummarizerService chatMemorySummarizerService;
  @MockBean private ChatProviderService chatProviderService;
  @MockBean private TokenUsageEstimator tokenUsageEstimator;

  @BeforeEach
  void setUpMocks() {
    when(chatMemorySummarizerService.isEnabled()).thenReturn(true);
    when(chatMemorySummarizerService.triggerTokenLimit()).thenReturn(0);
    when(chatMemorySummarizerService.targetTokenCount()).thenReturn(40);
    when(tokenUsageEstimator.estimate(ArgumentMatchers.any()))
        .thenAnswer(invocation -> new Estimate(500, 0, 500, false, false));
  }

  @Test
  void longFlowProducesSummaryAndHistoryContainsTail() {
    FlowSession session = persistSession();
    appendConversationEntries(session, 8);

    List<FlowMemoryVersion> versions =
        flowMemoryVersionRepository.findByFlowSessionAndChannelOrderByVersionAsc(
            session, FlowMemoryChannels.CONVERSATION);
    assertThat(versions).hasSize(8);
    List<JsonNode> initialHistory =
        flowMemoryService.history(session.getId(), FlowMemoryChannels.CONVERSATION, 20);
    assertThat(initialHistory)
        .withFailMessage("history=%s", initialHistory)
        .hasSize(8);

    mockSummarizerModel("Session summary");

    flowMemorySummarizerService.forceSummarize(
        session.getId(), "openai", "gpt-4o-mini", List.of(FlowMemoryChannels.CONVERSATION));

    List<FlowMemorySummary> summaries = summaryRepository.findAll();
    assertThat(summaries).hasSize(1);
    FlowMemorySummary summary = summaries.get(0);
    assertThat(summary.getSummaryText()).isEqualTo("Session summary");
    assertThat(summary.getMetadata().get("schemaVersion").asInt()).isEqualTo(1);

    List<JsonNode> history =
        flowMemoryService.history(session.getId(), FlowMemoryChannels.CONVERSATION, 10);
    assertThat(history).hasSize(1 + 4); // summary + tail
    JsonNode summaryNode = history.get(0);
    assertThat(summaryNode.path("type").asText()).isEqualTo("summary");
    assertThat(summaryNode.path("content").asText()).isEqualTo("Session summary");
    JsonNode tailEntry = history.get(1);
    assertThat(tailEntry.path("prompt").asText()).contains("message-5");
  }

  @Test
  void manualRebuildReplacesSummaryAndMetadata() {
    FlowSession session = persistSession();
    appendConversationEntries(session, 8);
    List<FlowMemoryVersion> versions =
        flowMemoryVersionRepository.findByFlowSessionAndChannelOrderByVersionAsc(
            session, FlowMemoryChannels.CONVERSATION);
    assertThat(versions).hasSize(8);
    List<JsonNode> initialHistory =
        flowMemoryService.history(session.getId(), FlowMemoryChannels.CONVERSATION, 20);
    assertThat(initialHistory)
        .withFailMessage("history=%s", initialHistory)
        .hasSize(8);
    mockSummarizerModel("Initial snapshot");

    flowMemorySummarizerService.forceSummarize(
        session.getId(), "openai", "gpt-4o-mini", List.of(FlowMemoryChannels.CONVERSATION));

    appendConversationEntries(session, 2);
    when(chatMemorySummarizerService.summarizeTranscript(
            ArgumentMatchers.eq(session.getId()), ArgumentMatchers.anyString(), ArgumentMatchers.eq("flow")))
        .thenReturn(Optional.of("Updated snapshot"));

    flowMemorySummarizerService.forceSummarize(
        session.getId(), "openai", "gpt-4o-mini", List.of(FlowMemoryChannels.CONVERSATION));

    List<FlowMemorySummary> summaries = summaryRepository.findAll();
    assertThat(summaries).hasSize(1);
    FlowMemorySummary summary = summaries.get(0);
    assertThat(summary.getSummaryText()).isEqualTo("Updated snapshot");

    List<JsonNode> history =
        flowMemoryService.history(session.getId(), FlowMemoryChannels.CONVERSATION, 10);
    assertThat(history.get(0).path("content").asText()).isEqualTo("Updated snapshot");
  }

  private FlowSession persistSession() {
    FlowDefinition definition =
        flowDefinitionRepository.save(
            new FlowDefinition(
                "integration-flow",
                1,
                FlowDefinitionStatus.PUBLISHED,
                true,
                com.aiadvent.backend.flow.TestFlowBlueprintFactory.simpleBlueprint()));
    FlowSession session =
        new FlowSession(definition, 1, FlowSessionStatus.RUNNING, 0L, 0L);
    session.setChatSessionId(UUID.randomUUID());
    session = flowSessionRepository.save(session);
    flowSessionRepository.flush();
    return session;
  }

  private void appendConversationEntries(FlowSession session, int count) {
    for (int i = 1; i <= count; i++) {
      FlowMemoryMetadata metadata =
          FlowMemoryMetadata.builder()
              .sourceType(i % 2 == 0 ? FlowMemorySourceType.AGENT_OUTPUT : FlowMemorySourceType.USER_INPUT)
              .stepId("step-" + ((i + 1) / 2))
              .stepAttempt((i + 1) / 2)
              .createdByStepId(UUID.randomUUID())
              .build();
      ObjectNode payload = objectMapper.createObjectNode();
      payload.put("prompt", "message-" + i);
      flowMemoryService.append(
          session.getId(), FlowMemoryChannels.CONVERSATION, payload, metadata);
    }
    flowMemoryVersionRepository.flush();
  }

  private void mockSummarizerModel(String summaryText) {
    when(chatMemorySummarizerService.tryAcquireSlot()).thenReturn(true);
    when(chatMemorySummarizerService.summarizeTranscript(
            ArgumentMatchers.any(UUID.class), ArgumentMatchers.anyString(), ArgumentMatchers.eq("flow")))
        .thenReturn(Optional.of(summaryText));

    ChatProvidersProperties.Model model = new ChatProvidersProperties.Model();
    ChatProvidersProperties.Usage usage = new ChatProvidersProperties.Usage();
    usage.setFallbackTokenizer("cl100k_base");
    model.setUsage(usage);
    when(chatProviderService.model("openai", "gpt-4o-mini")).thenReturn(model);
  }

  static class TestConfig {
    @Bean
    ObjectMapper objectMapper() {
      return new ObjectMapper();
    }

    @Bean
    SimpleMeterRegistry meterRegistry() {
      return new SimpleMeterRegistry();
    }

    @Bean
    @org.springframework.context.annotation.Primary
    com.aiadvent.backend.chat.config.ChatMemoryProperties chatMemoryProperties() {
      var properties = new com.aiadvent.backend.chat.config.ChatMemoryProperties();
      properties.setWindowSize(4);
      properties.getSummarization().setEnabled(true);
      properties.getSummarization().setTriggerTokenLimit(0);
      properties.getSummarization().setTargetTokenCount(40);
      return properties;
    }
  }
}
