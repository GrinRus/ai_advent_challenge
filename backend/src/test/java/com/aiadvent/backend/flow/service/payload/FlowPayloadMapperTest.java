package com.aiadvent.backend.flow.service.payload;

import static org.assertj.core.api.Assertions.assertThat;

import com.aiadvent.backend.chat.config.ChatProviderType;
import com.aiadvent.backend.chat.provider.model.UsageCostEstimate;
import com.aiadvent.backend.chat.provider.model.UsageSource;
import com.aiadvent.backend.flow.TestFlowBlueprintFactory;
import com.aiadvent.backend.flow.agent.model.AgentDefaultOptions;
import com.aiadvent.backend.flow.domain.AgentDefinition;
import com.aiadvent.backend.flow.domain.AgentVersion;
import com.aiadvent.backend.flow.domain.AgentVersionStatus;
import com.aiadvent.backend.flow.domain.FlowDefinition;
import com.aiadvent.backend.flow.domain.FlowDefinitionStatus;
import com.aiadvent.backend.flow.domain.FlowSession;
import com.aiadvent.backend.flow.domain.FlowSessionStatus;
import com.aiadvent.backend.flow.domain.FlowStepExecution;
import com.aiadvent.backend.flow.domain.FlowStepStatus;
import com.aiadvent.backend.flow.execution.model.FlowEventPayload;
import com.aiadvent.backend.flow.execution.model.FlowStepInputPayload;
import com.aiadvent.backend.flow.session.model.FlowLaunchParameters;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FlowPayloadMapperTest {

  private final ObjectMapper objectMapper = new ObjectMapper();
  private FlowPayloadMapper mapper;
  private FlowSession session;
  private FlowStepExecution stepExecution;
  private AgentVersion agentVersion;

  @BeforeEach
  void setUp() {
    mapper = new FlowPayloadMapper(objectMapper);

    FlowDefinition definition =
        new FlowDefinition(
            "flow", 1, FlowDefinitionStatus.PUBLISHED, true, TestFlowBlueprintFactory.simpleBlueprint());
    setField(definition, "id", UUID.randomUUID());

    session = new FlowSession(definition, definition.getVersion(), FlowSessionStatus.RUNNING, 1L, 0L);
    setField(session, "id", UUID.randomUUID());
    session.setLaunchParameters(parseLaunchParameters("{\"foo\":\"bar\"}"));
    session.setSharedContext(mapper.initializeSharedContext(objectMapper.createObjectNode().put("init", 1)));

    stepExecution = new FlowStepExecution(session, "step-1", FlowStepStatus.PENDING, 1);
    setField(stepExecution, "id", UUID.randomUUID());
    stepExecution.setStepName("Name");
    stepExecution.setPrompt("Prompt");

    agentVersion =
        new AgentVersion(
            new AgentDefinition("agent", "display", null, true),
            1,
            AgentVersionStatus.PUBLISHED,
            ChatProviderType.OPENAI,
            "openai",
            "gpt-4.0");
    setField(agentVersion, "id", UUID.randomUUID());
    agentVersion.setDefaultOptions(AgentDefaultOptions.from(objectMapper.createObjectNode().put("temperature", 0.2)));
    stepExecution.setAgentVersion(agentVersion);
  }

  @Test
  void buildStepInputPayloadIncludesLaunchParametersAndSharedSections() {
    FlowStepInputPayload payload = mapper.buildStepInputPayload(session);

    var json = payload.asJson();
    assertThat(json.path("launchParameters").path("foo").asText()).isEqualTo("bar");
    assertThat(json.path("sharedContext").path("initial").path("init").asInt()).isEqualTo(1);
    assertThat(json.path("currentContext")).isNotNull();
  }

  @Test
  void eventPayloadWrapsStepMetadataAndContext() {
    ObjectNode original = objectMapper.createObjectNode().put("content", "result");
    FlowEventPayload payload = mapper.eventPayload(session, stepExecution, original);

    var json = payload.asJson();
    assertThat(json.path("content").asText()).isEqualTo("result");
    assertThat(json.path("context").path("launchParameters").path("foo").asText()).isEqualTo("bar");
    assertThat(json.path("step").path("stepExecutionId").asText()).isEqualTo(stepExecution.getId().toString());
    assertThat(json.path("step").path("agentVersion").path("modelId").asText()).isEqualTo("gpt-4.0");
  }

  @Test
  void usageAndCostPayloadsReflectUsageEstimate() {
    UsageCostEstimate estimate =
        new UsageCostEstimate(
            50,
            10,
            60,
            BigDecimal.valueOf(0.05),
            BigDecimal.valueOf(0.01),
            BigDecimal.valueOf(0.06),
            "USD",
            UsageSource.NATIVE);

    var usagePayload = mapper.usagePayload(estimate);
    var costPayload = mapper.costPayload(estimate);

    assertThat(usagePayload.asJson().path("totalTokens").asInt()).isEqualTo(60);
    assertThat(costPayload.asJson().path("total").asDouble()).isEqualTo(0.06);
    assertThat(costPayload.asJson().path("currency").asText()).isEqualTo("USD");
  }

  private static void setField(Object target, String field, Object value) {
    try {
      var f = target.getClass().getDeclaredField(field);
      f.setAccessible(true);
      f.set(target, value);
    } catch (ReflectiveOperationException exception) {
      throw new IllegalStateException(exception);
    }
  }

  private static FlowLaunchParameters parseLaunchParameters(String json) {
    try {
      return FlowLaunchParameters.from(new ObjectMapper().readTree(json));
    } catch (Exception exception) {
      throw new IllegalStateException(exception);
    }
  }
}
