package com.aiadvent.backend.flow.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.aiadvent.backend.chat.provider.model.ChatRequestOverrides;
import com.aiadvent.backend.chat.support.StubChatClientConfiguration;
import com.aiadvent.backend.chat.support.StubChatClientState;
import com.aiadvent.backend.flow.blueprint.FlowBlueprint;
import com.aiadvent.backend.flow.blueprint.FlowBlueprintMemory;
import com.aiadvent.backend.flow.blueprint.FlowBlueprintMetadata;
import com.aiadvent.backend.flow.blueprint.FlowBlueprintStep;
import com.aiadvent.backend.flow.blueprint.FlowStepTransitionsDraft;
import com.aiadvent.backend.flow.domain.AgentDefinition;
import com.aiadvent.backend.flow.domain.AgentVersion;
import com.aiadvent.backend.flow.domain.FlowDefinition;
import com.aiadvent.backend.flow.domain.FlowDefinitionStatus;
import com.aiadvent.backend.flow.domain.FlowSession;
import com.aiadvent.backend.flow.domain.FlowSessionStatus;
import com.aiadvent.backend.flow.persistence.AgentDefinitionRepository;
import com.aiadvent.backend.flow.persistence.AgentVersionRepository;
import com.aiadvent.backend.flow.persistence.FlowDefinitionRepository;
import com.aiadvent.backend.flow.persistence.FlowSessionRepository;
import com.aiadvent.backend.flow.session.model.FlowLaunchParameters;
import com.aiadvent.backend.flow.session.model.FlowOverrides;
import com.aiadvent.backend.flow.session.model.FlowSharedContext;
import com.aiadvent.backend.support.PostgresTestContainer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(
    properties = {
      "spring.ai.openai.api-key=test-token",
      "spring.ai.openai.base-url=http://localhost",
      "app.chat.default-provider=stub",
      "app.chat.memory.window-size=3",
      "app.chat.memory.retention=PT24H",
      "app.chat.memory.cleanup-interval=PT1H"
    })
@ActiveProfiles("test")
@Import(StubChatClientConfiguration.class)
class AgentInvocationServiceMcpIntegrationTest extends PostgresTestContainer {

  @Autowired private AgentInvocationService agentInvocationService;

  @Autowired private FlowSessionRepository flowSessionRepository;

  @Autowired private FlowDefinitionRepository flowDefinitionRepository;

  @Autowired private AgentDefinitionRepository agentDefinitionRepository;

  @Autowired private AgentVersionRepository agentVersionRepository;

  @Autowired private ObjectMapper objectMapper;

  @BeforeEach
  void resetStubState() {
    StubChatClientState.reset();
  }

  @AfterEach
  void cleanupStubState() {
    StubChatClientState.reset();
  }

  @Transactional
  @Test
  void invokeResolvesManualMcpToolWhenRequested() {
    StubChatClientState.setSyncResponses(List.of("Flow Ops stub response."));

    AgentVersion agentVersion = resolveFlowOpsAgentVersion();

    FlowDefinition flowDefinition = createFlowDefinition(agentVersion);
    flowDefinitionRepository.saveAndFlush(flowDefinition);

    FlowSession flowSession = createFlowSession(flowDefinition);
    flowSessionRepository.saveAndFlush(flowSession);

    ObjectNode interactionPayload = objectMapper.createObjectNode();
    interactionPayload.putArray("toolCodes").add("flow_ops.list_flows");
    ObjectNode interactionNode = objectMapper.createObjectNode();
    interactionNode.set("payload", interactionPayload);
    ObjectNode inputContext = objectMapper.createObjectNode();
    inputContext.set("interaction", interactionNode);

    ObjectNode launchParameters = objectMapper.createObjectNode();
    launchParameters.put("environment", "qa");

    AgentInvocationRequest request =
        new AgentInvocationRequest(
            flowSession.getId(),
            UUID.randomUUID(),
            agentVersion,
            "Summarise recent flow activity.",
            inputContext,
            launchParameters,
            ChatRequestOverrides.empty(),
            ChatRequestOverrides.empty(),
            List.of(),
            List.of());

    AgentInvocationResult result = agentInvocationService.invoke(request);

    assertThat(result.content()).isEqualTo("Flow Ops stub response.");
    assertThat(result.selectedToolCodes()).containsExactly("flow_ops.list_flows");
    assertThat(StubChatClientState.lastPrompt()).isNotNull();
  }

  private AgentVersion resolveFlowOpsAgentVersion() {
    AgentDefinition definition =
        agentDefinitionRepository
            .findByIdentifier("flow-ops-operator")
            .orElseThrow(() -> new IllegalStateException("Seeded flow-ops-operator agent missing"));
    return agentVersionRepository
        .findByAgentDefinitionAndVersion(definition, 1)
        .orElseThrow(() -> new IllegalStateException("Seeded flow-ops-operator agent version missing"));
  }

  private FlowDefinition createFlowDefinition(AgentVersion agentVersion) {
    FlowBlueprintStep step =
        new FlowBlueprintStep(
            "start",
            "Flow Ops E2E",
            agentVersion.getId().toString(),
            "Use Flow Ops MCP tooling to gather diagnostics.",
            null,
            null,
            List.of(),
            List.of(),
            new FlowStepTransitionsDraft(null, null),
            1);

    FlowBlueprint blueprint =
        new FlowBlueprint(
            1,
            FlowBlueprintMetadata.fromLegacy("Flow Ops E2E", "Integration test blueprint"),
            null,
            null,
            step.id(),
            Boolean.TRUE,
            List.of(),
            FlowBlueprintMemory.empty(),
            List.of(step));

    FlowDefinition definition =
        new FlowDefinition(
            "flow-ops-mcp-invoke-" + UUID.randomUUID(),
            1,
            FlowDefinitionStatus.PUBLISHED,
            true,
            blueprint);
    definition.setPublishedAt(Instant.now());
    definition.setUpdatedBy("test");
    return definition;
  }

  private FlowSession createFlowSession(FlowDefinition definition) {
    FlowSession session =
        new FlowSession(definition, definition.getVersion(), FlowSessionStatus.RUNNING, 0L, 0L);
    session.setCurrentStepId("start");
    ObjectNode launchParameters = objectMapper.createObjectNode();
    launchParameters.put("environment", "qa");
    session.setLaunchParameters(FlowLaunchParameters.from(launchParameters));
    session.setSharedContext(FlowSharedContext.empty());
    session.setLaunchOverrides(FlowOverrides.empty());
    session.setStartedAt(Instant.now());
    return session;
  }
}
