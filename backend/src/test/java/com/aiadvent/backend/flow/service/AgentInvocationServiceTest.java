package com.aiadvent.backend.flow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aiadvent.backend.chat.config.ChatProviderType;
import com.aiadvent.backend.chat.config.ChatProvidersProperties;
import com.aiadvent.backend.chat.provider.ChatProviderService;
import com.aiadvent.backend.chat.provider.model.ChatProviderSelection;
import com.aiadvent.backend.chat.provider.model.ChatRequestOverrides;
import com.aiadvent.backend.chat.provider.model.UsageCostEstimate;
import com.aiadvent.backend.chat.provider.model.UsageSource;
import com.aiadvent.backend.flow.domain.AgentDefinition;
import com.aiadvent.backend.flow.domain.AgentVersion;
import com.aiadvent.backend.flow.domain.AgentVersionStatus;
import com.aiadvent.backend.flow.domain.FlowSession;
import com.aiadvent.backend.flow.persistence.FlowSessionRepository;
import com.aiadvent.backend.flow.memory.FlowMemoryChannels;
import com.aiadvent.backend.flow.memory.FlowMemoryService;
import com.aiadvent.backend.flow.memory.FlowMemorySummarizerService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

class AgentInvocationServiceTest {

  private ChatProviderService chatProviderService;
  private FlowSessionRepository flowSessionRepository;
  private FlowMemoryService flowMemoryService;
  private FlowMemorySummarizerService flowMemorySummarizerService;
  private ObjectMapper objectMapper;

  private AgentInvocationService agentInvocationService;

  @BeforeEach
  void setUp() {
    chatProviderService = mock(ChatProviderService.class);
    flowSessionRepository = mock(FlowSessionRepository.class);
    flowMemoryService = mock(FlowMemoryService.class);
    flowMemorySummarizerService = mock(FlowMemorySummarizerService.class);
    objectMapper = new ObjectMapper();

    agentInvocationService =
        new AgentInvocationService(
            chatProviderService,
            flowSessionRepository,
            flowMemoryService,
            flowMemorySummarizerService,
            objectMapper);
  }

  @Test
  void invokeReturnsDetailedResultWithContextAndOverrides() {
    UUID sessionId = UUID.randomUUID();
    FlowSession flowSession = mock(FlowSession.class);
    when(flowSession.getId()).thenReturn(sessionId);
    when(flowSessionRepository.findById(sessionId)).thenReturn(Optional.of(flowSession));

    AgentVersion agentVersion =
        new AgentVersion(
            new AgentDefinition("agent-id", "Agent", null, true),
            1,
            AgentVersionStatus.PUBLISHED,
            ChatProviderType.OPENAI,
            "openai",
            "gpt-4o-mini");
    agentVersion.setSystemPrompt("Base agent system prompt");
    agentVersion.setMaxTokens(1024);
    ObjectNode defaultOptions = objectMapper.createObjectNode();
    defaultOptions.put("temperature", 0.2);
    defaultOptions.put("topP", 0.4);
    defaultOptions.put("maxTokens", 1000);
    agentVersion.setDefaultOptions(defaultOptions);

    ChatProviderSelection selection = new ChatProviderSelection("openai", "gpt-4o-mini");
    when(chatProviderService.resolveSelection(agentVersion.getProviderId(), agentVersion.getModelId()))
        .thenReturn(selection);
    when(chatProviderService.provider(selection.providerId()))
        .thenReturn(new ChatProvidersProperties.Provider());

    Generation generation =
        new Generation(AssistantMessage.builder().content("model response").build());
    ChatResponse chatResponse = new ChatResponse(List.of(generation));
    when(chatProviderService.chatSyncWithOverrides(
            eq(selection), eq(agentVersion.getSystemPrompt()), anyList(), anyMap(), anyString(), any()))
        .thenReturn(chatResponse);

    UsageCostEstimate usageCost =
        new UsageCostEstimate(
            120,
            30,
            150,
            BigDecimal.valueOf(0.12),
            BigDecimal.valueOf(0.03),
            BigDecimal.valueOf(0.15),
            "USD",
            UsageSource.NATIVE);
    when(chatProviderService.estimateUsageCost(eq(selection), any(), anyString(), anyString()))
        .thenReturn(usageCost);

    ObjectNode launchParameters = objectMapper.createObjectNode();
    launchParameters.put("foo", "bar");

    ObjectNode sharedContext = objectMapper.createObjectNode();
    sharedContext.put("notes", "test");
    ObjectNode inputContext = objectMapper.createObjectNode();
    inputContext.set("sharedContext", sharedContext);
    inputContext.set("launchParameters", launchParameters.deepCopy());
    ObjectNode lastOutput = objectMapper.createObjectNode();
    lastOutput.put("value", "prev");
    inputContext.set("lastOutput", lastOutput);

    ObjectNode memoryEntry = objectMapper.createObjectNode();
    memoryEntry.put("content", "memory note");
    when(flowMemoryService.history(sessionId, "shared", 3)).thenReturn(List.of(memoryEntry));
    when(flowMemorySummarizerService.supportsChannel(anyString())).thenReturn(false);

    ChatRequestOverrides stepOverrides = new ChatRequestOverrides(0.3, null, 2048);
    ChatRequestOverrides sessionOverrides = new ChatRequestOverrides(null, 0.9, null);

    AgentInvocationRequest request =
        new AgentInvocationRequest(
            sessionId,
            UUID.randomUUID(),
            agentVersion,
            "  Solve the problem ",
            inputContext,
            launchParameters,
            stepOverrides,
            sessionOverrides,
            List.of(new MemoryReadInstruction("shared", 3)),
            List.of());

    AgentInvocationResult result = agentInvocationService.invoke(request);

    assertThat(result.content()).isEqualTo("model response");
    assertThat(result.usageCost()).isEqualTo(usageCost);
    assertThat(result.memoryUpdates()).isEmpty();
    assertThat(result.providerSelection()).isEqualTo(selection);
    assertThat(result.appliedOverrides().temperature()).isEqualTo(0.3);
    assertThat(result.appliedOverrides().topP()).isEqualTo(0.9);
    assertThat(result.appliedOverrides().maxTokens()).isEqualTo(2048);
    assertThat(result.systemPrompt()).isEqualTo(agentVersion.getSystemPrompt());
    assertThat(result.memorySnapshots()).hasSize(1);
    assertThat(result.memorySnapshots().get(0)).contains("memory note");
    assertThat(result.userMessage()).contains("Solve the problem");
    assertThat(result.userMessage()).contains("Launch Parameters");
    assertThat(result.userMessage()).contains("\"foo\" : \"bar\"");
    assertThat(result.userMessage()).contains("\"notes\" : \"test\"");
    assertThat(result.userMessage()).contains("\"value\" : \"prev\"");

    ArgumentCaptor<List> memoryCaptor = ArgumentCaptor.forClass(List.class);
    ArgumentCaptor<String> userMessageCaptor = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<ChatRequestOverrides> overridesCaptor = ArgumentCaptor.forClass(ChatRequestOverrides.class);
    verify(chatProviderService)
        .chatSyncWithOverrides(
            eq(selection),
            eq(agentVersion.getSystemPrompt()),
            memoryCaptor.capture(),
            anyMap(),
            userMessageCaptor.capture(),
            overridesCaptor.capture());

    @SuppressWarnings("unchecked")
    List<String> capturedMemory = memoryCaptor.getValue();
    assertThat(result.memorySnapshots()).isEqualTo(capturedMemory);
    assertThat(result.userMessage()).isEqualTo(userMessageCaptor.getValue());
    assertThat(result.appliedOverrides().temperature()).isEqualTo(overridesCaptor.getValue().temperature());
    assertThat(result.appliedOverrides().topP()).isEqualTo(overridesCaptor.getValue().topP());
    assertThat(result.appliedOverrides().maxTokens()).isEqualTo(overridesCaptor.getValue().maxTokens());
  }

  @Test
  void triggerFlowSummariesIncludesConversationByDefault() {
    UUID sessionId = UUID.randomUUID();
    FlowSession flowSession = mock(FlowSession.class);
    when(flowSession.getId()).thenReturn(sessionId);
    when(flowSessionRepository.findById(sessionId)).thenReturn(Optional.of(flowSession));

    AgentVersion agentVersion =
        new AgentVersion(
            new AgentDefinition("agent-flow", "Flow Agent", null, true),
            1,
            AgentVersionStatus.PUBLISHED,
            ChatProviderType.OPENAI,
            "openai",
            "gpt-4o-mini");
    agentVersion.setSystemPrompt("System");

    ChatProviderSelection selection = new ChatProviderSelection("openai", "gpt-4o-mini");
    when(chatProviderService.resolveSelection(agentVersion.getProviderId(), agentVersion.getModelId()))
        .thenReturn(selection);
    when(chatProviderService.provider(selection.providerId()))
        .thenReturn(new ChatProvidersProperties.Provider());

    ChatResponse chatResponse =
        new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("ok").build())));
    when(chatProviderService.chatSyncWithOverrides(
            eq(selection), eq(agentVersion.getSystemPrompt()), anyList(), anyMap(), anyString(), any()))
        .thenReturn(chatResponse);

    UsageCostEstimate usageCost =
        new UsageCostEstimate(
            0,
            0,
            0,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            "USD",
            UsageSource.NATIVE);
    when(chatProviderService.estimateUsageCost(eq(selection), any(), anyString(), anyString()))
        .thenReturn(usageCost);

    when(flowMemorySummarizerService.supportsChannel(FlowMemoryChannels.CONVERSATION)).thenReturn(true);
    when(
            flowMemorySummarizerService.preflight(
                eq(sessionId),
                eq(FlowMemoryChannels.CONVERSATION),
                eq(selection.providerId()),
                eq(selection.modelId()),
                anyString()))
        .thenReturn(Optional.empty());

    AgentInvocationRequest request =
        new AgentInvocationRequest(
            sessionId,
            UUID.randomUUID(),
            agentVersion,
            "Hello!",
            objectMapper.createObjectNode(),
            objectMapper.createObjectNode(),
            ChatRequestOverrides.empty(),
            ChatRequestOverrides.empty(),
            List.of(),
            List.of());

    agentInvocationService.invoke(request);

    verify(flowMemorySummarizerService)
        .preflight(
            eq(sessionId),
            eq(FlowMemoryChannels.CONVERSATION),
            eq(selection.providerId()),
            eq(selection.modelId()),
            anyString());
  }
}
