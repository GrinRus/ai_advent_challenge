package com.aiadvent.backend.flow.service;

import static org.assertj.core.api.Assertions.assertThat;

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
import com.aiadvent.backend.flow.domain.FlowJob;
import com.aiadvent.backend.flow.domain.FlowJobStatus;
import com.aiadvent.backend.flow.domain.FlowSession;
import com.aiadvent.backend.flow.domain.FlowSessionStatus;
import com.aiadvent.backend.flow.domain.FlowStepExecution;
import com.aiadvent.backend.flow.domain.FlowStepStatus;
import com.aiadvent.backend.flow.execution.model.FlowStepInputPayload;
import com.aiadvent.backend.flow.persistence.AgentDefinitionRepository;
import com.aiadvent.backend.flow.persistence.AgentVersionRepository;
import com.aiadvent.backend.flow.persistence.FlowDefinitionRepository;
import com.aiadvent.backend.flow.persistence.FlowJobRepository;
import com.aiadvent.backend.flow.persistence.FlowSessionRepository;
import com.aiadvent.backend.flow.persistence.FlowStepExecutionRepository;
import com.aiadvent.backend.flow.session.model.FlowLaunchParameters;
import com.aiadvent.backend.flow.session.model.FlowOverrides;
import com.aiadvent.backend.flow.session.model.FlowSharedContext;
import com.aiadvent.backend.support.PostgresTestContainer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
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
class AgentOrchestratorServiceMcpIntegrationTest extends PostgresTestContainer {

  @Autowired private AgentOrchestratorService orchestratorService;

  @Autowired private FlowDefinitionRepository flowDefinitionRepository;

  @Autowired private FlowSessionRepository flowSessionRepository;

  @Autowired private FlowStepExecutionRepository flowStepExecutionRepository;

  @Autowired private FlowJobRepository flowJobRepository;

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
  void orchestratorConsumesRequestedMcpToolsFromInputContext() {
    StubChatClientState.setSyncResponses(List.of("Flow orchestrator stub response."));

    AgentVersion agentVersion = resolveFlowOpsAgentVersion();
    FlowDefinition definition = createFlowDefinition(agentVersion);
    flowDefinitionRepository.saveAndFlush(definition);

    UUID chatSessionId = UUID.randomUUID();
    FlowSession session =
        orchestratorService.start(
            definition.getId(),
            FlowLaunchParameters.empty(),
            FlowSharedContext.empty(),
            FlowOverrides.empty(),
            chatSessionId);

    List<FlowStepExecution> executions =
        flowStepExecutionRepository.findByFlowSessionOrderByCreatedAtAsc(session);
    assertThat(executions).hasSize(1);
    FlowStepExecution stepExecution = executions.get(0);

    ObjectNode interactionPayload = objectMapper.createObjectNode();
    interactionPayload.putArray("toolCodes").add("flow_ops.list_flows");
    ObjectNode interactionNode = objectMapper.createObjectNode();
    interactionNode.set("payload", interactionPayload);
    ObjectNode overridesNode = objectMapper.createObjectNode();
    overridesNode.put("temperature", 0.25);
    ObjectNode inputPayload = objectMapper.createObjectNode();
    inputPayload.set("interaction", interactionNode);
    inputPayload.set("overrides", overridesNode);
    stepExecution.setInputPayload(FlowStepInputPayload.from(inputPayload));
    flowStepExecutionRepository.saveAndFlush(stepExecution);

    Optional<FlowJob> maybeJob = orchestratorService.processNextJob("worker-mcp");
    assertThat(maybeJob).isPresent();

    FlowJob job = maybeJob.get();
    assertThat(job.getStatus()).isEqualTo(FlowJobStatus.COMPLETED);

    FlowStepExecution refreshedExecution =
        flowStepExecutionRepository
            .findById(stepExecution.getId())
            .orElseThrow(() -> new IllegalStateException("Step execution disappeared after processing"));

    assertThat(refreshedExecution.getStatus()).isEqualTo(FlowStepStatus.COMPLETED);
    JsonNode output = refreshedExecution.getOutputPayload().asJson();
    JsonNode selectedTools = output.path("request").path("selectedTools");
    assertThat(selectedTools.isArray()).isTrue();
    assertThat(selectedTools).anyMatch(node -> "flow_ops.list_flows".equals(node.asText()));
    assertThat(StubChatClientState.lastPrompt()).isNotNull();

    FlowSession refreshedSession =
        flowSessionRepository
            .findById(session.getId())
            .orElseThrow(() -> new IllegalStateException("Flow session missing after orchestration"));
    assertThat(refreshedSession.getStatus()).isEqualTo(FlowSessionStatus.COMPLETED);

    FlowJob persistedJob =
        flowJobRepository
            .findById(job.getId())
            .orElseThrow(() -> new IllegalStateException("Flow job missing after orchestration"));
    assertThat(persistedJob.getStatus()).isEqualTo(FlowJobStatus.COMPLETED);
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
            "flow-ops-mcp-orchestrator-" + UUID.randomUUID(),
            1,
            FlowDefinitionStatus.PUBLISHED,
            true,
            blueprint);
    definition.setPublishedAt(Instant.now());
    definition.setUpdatedBy("test");
    return definition;
  }
}
