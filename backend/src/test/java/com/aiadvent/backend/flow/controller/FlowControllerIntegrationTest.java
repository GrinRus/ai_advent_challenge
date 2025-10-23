package com.aiadvent.backend.flow.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aiadvent.backend.flow.api.FlowEventDto;
import com.aiadvent.backend.flow.api.FlowStartResponse;
import com.aiadvent.backend.flow.domain.AgentDefinition;
import com.aiadvent.backend.flow.domain.AgentVersion;
import com.aiadvent.backend.flow.domain.AgentVersionStatus;
import com.aiadvent.backend.flow.domain.FlowDefinition;
import com.aiadvent.backend.flow.domain.FlowDefinitionStatus;
import com.aiadvent.backend.flow.domain.FlowJob;
import com.aiadvent.backend.flow.domain.FlowEvent;
import com.aiadvent.backend.flow.domain.FlowEventType;
import com.aiadvent.backend.flow.domain.FlowSession;
import com.aiadvent.backend.flow.domain.FlowSessionStatus;
import com.aiadvent.backend.flow.job.JobQueuePort;
import com.aiadvent.backend.flow.persistence.AgentDefinitionRepository;
import com.aiadvent.backend.flow.persistence.AgentVersionRepository;
import com.aiadvent.backend.flow.persistence.FlowDefinitionRepository;
import com.aiadvent.backend.flow.persistence.FlowEventRepository;
import com.aiadvent.backend.flow.persistence.FlowSessionRepository;
import com.aiadvent.backend.flow.persistence.FlowStepExecutionRepository;
import com.aiadvent.backend.flow.service.AgentInvocationRequest;
import com.aiadvent.backend.flow.service.AgentInvocationResult;
import com.aiadvent.backend.flow.service.AgentInvocationService;
import com.aiadvent.backend.flow.service.AgentOrchestratorService;
import com.aiadvent.backend.flow.service.FlowControlService;
import com.aiadvent.backend.flow.service.FlowStatusService.FlowStatusResponse;
import com.aiadvent.backend.chat.provider.model.UsageCostEstimate;
import com.aiadvent.backend.support.PostgresTestContainer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(
    properties = {
      "spring.ai.openai.api-key=test-token",
      "spring.ai.openai.base-url=http://localhost",
      "app.chat.default-provider=stub"
    })
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FlowControllerIntegrationTest extends PostgresTestContainer {

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private FlowDefinitionRepository flowDefinitionRepository;
  @Autowired private AgentDefinitionRepository agentDefinitionRepository;
  @Autowired private AgentVersionRepository agentVersionRepository;
  @Autowired private FlowSessionRepository flowSessionRepository;
  @Autowired private FlowEventRepository flowEventRepository;
  @Autowired private FlowStepExecutionRepository flowStepExecutionRepository;
  @Autowired private AgentOrchestratorService orchestratorService;
  @Autowired private FlowControlService flowControlService;
  @Autowired private JdbcTemplate jdbcTemplate;

  @MockBean private AgentInvocationService agentInvocationService;

  @BeforeEach
  void cleanDatabase() {
    jdbcTemplate.execute("TRUNCATE TABLE flow_job RESTART IDENTITY CASCADE");
    flowEventRepository.deleteAll();
    flowStepExecutionRepository.deleteAll();
    flowSessionRepository.deleteAll();
    flowDefinitionRepository.deleteAll();
    agentVersionRepository.deleteAll();
    agentDefinitionRepository.deleteAll();
    Mockito.reset(agentInvocationService);
  }

  @Test
  void startProcessAndFetchFlowStatus() throws Exception {
    AgentVersion agentVersion = persistAgent();
    FlowDefinition definition = persistFlowDefinition(agentVersion.getId());

    Mockito.when(agentInvocationService.invoke(Mockito.any()))
        .thenReturn(
            new AgentInvocationResult(
                "Completed result",
                new UsageCostEstimate(10, 5, 15, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, "USD", com.aiadvent.backend.chat.provider.model.UsageSource.NATIVE),
                List.of()));

    FlowStartResponse startResponse = startFlow(definition.getId(), null);

    orchestratorService.processNextJob("worker-1");

    MvcResult snapshotResult =
        mockMvc
            .perform(get("/api/flows/" + startResponse.sessionId() + "/snapshot"))
            .andExpect(status().isOk())
            .andReturn();

    FlowStatusResponse statusResponse =
        objectMapper.readValue(snapshotResult.getResponse().getContentAsString(), FlowStatusResponse.class);

    assertThat(statusResponse.state().status()).isEqualTo(FlowSessionStatus.COMPLETED);
    assertThat(statusResponse.events()).extracting(FlowEventDto::type)
        .containsExactly(FlowEventType.FLOW_STARTED, FlowEventType.STEP_STARTED, FlowEventType.STEP_COMPLETED, FlowEventType.FLOW_COMPLETED);

    FlowSession session = flowSessionRepository.findById(startResponse.sessionId()).orElseThrow();
    assertThat(session.getStatus()).isEqualTo(FlowSessionStatus.COMPLETED);

    // control endpoint: pause then resume
    mockMvc
        .perform(
            post("/api/flows/" + startResponse.sessionId() + "/control")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"command\":\"pause\"}"))
        .andExpect(status().isOk());
    mockMvc
        .perform(
            post("/api/flows/" + startResponse.sessionId() + "/control")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"command\":\"resume\"}"))
        .andExpect(status().isOk());
  }

  @Test
  void startFlowPropagatesLaunchParametersAndOverrides() throws Exception {
    AgentVersion agentVersion = persistAgent();
    FlowDefinition definition = persistFlowDefinition(agentVersion.getId());

    Mockito.when(agentInvocationService.invoke(Mockito.any()))
        .thenReturn(
            new AgentInvocationResult(
                "Agent output",
                new UsageCostEstimate(
                    20,
                    10,
                    30,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    "USD",
                    com.aiadvent.backend.chat.provider.model.UsageSource.NATIVE),
                List.of()));

    ObjectNode body = objectMapper.createObjectNode();
    ObjectNode parameters = body.putObject("parameters");
    parameters.put("customerId", "12345");
    ObjectNode sharedContext = body.putObject("sharedContext");
    sharedContext.put("channel", "email");
    ObjectNode overrides = body.putObject("overrides");
    overrides.put("temperature", 0.2);
    overrides.put("maxTokens", 256);

    FlowStartResponse startResponse =
        startFlow(definition.getId(), objectMapper.writeValueAsString(body));

    assertThat(startResponse.launchParameters()).isNotNull();
    assertThat(startResponse.overrides()).isNotNull();
    assertThat(startResponse.overrides().temperature()).isEqualTo(0.2d);
    assertThat(startResponse.sharedContext()).isNotNull();
    assertThat(startResponse.sharedContext().get("initial")).isNotNull();

    orchestratorService.processNextJob("worker-1");

    ArgumentCaptor<AgentInvocationRequest> captor =
        ArgumentCaptor.forClass(AgentInvocationRequest.class);
    Mockito.verify(agentInvocationService).invoke(captor.capture());
    AgentInvocationRequest invocationRequest = captor.getValue();

    assertThat(invocationRequest.launchParameters()).isNotNull();
    assertThat(invocationRequest.launchParameters().get("customerId").asText()).isEqualTo("12345");
    assertThat(invocationRequest.sessionOverrides()).isNotNull();
    assertThat(invocationRequest.sessionOverrides().temperature()).isEqualTo(0.2d);
    assertThat(invocationRequest.sessionOverrides().maxTokens()).isEqualTo(256);

    MvcResult snapshotResult =
        mockMvc
            .perform(get("/api/flows/" + startResponse.sessionId() + "/snapshot"))
            .andExpect(status().isOk())
            .andReturn();

    FlowStatusResponse statusResponse =
        objectMapper.readValue(snapshotResult.getResponse().getContentAsString(), FlowStatusResponse.class);

    FlowEventDto stepCompletedEvent =
        statusResponse.events().stream()
            .filter(event -> event.type() == FlowEventType.STEP_COMPLETED)
            .findFirst()
            .orElseThrow();

    @SuppressWarnings("unchecked")
    Map<String, Object> payload = (Map<String, Object>) stepCompletedEvent.payload();
    assertThat(payload).containsKey("context");

    @SuppressWarnings("unchecked")
    Map<String, Object> context = (Map<String, Object>) payload.get("context");
    assertThat(context).containsKeys("launchParameters", "launchOverrides");

    @SuppressWarnings("unchecked")
    Map<String, Object> launchParametersContext =
        (Map<String, Object>) context.get("launchParameters");
    assertThat(launchParametersContext).containsEntry("customerId", "12345");

    @SuppressWarnings("unchecked")
    Map<String, Object> launchOverridesContext =
        (Map<String, Object>) context.get("launchOverrides");
    assertThat(launchOverridesContext).containsEntry("temperature", 0.2);
    assertThat(launchOverridesContext).containsEntry("maxTokens", 256);
  }

  @Test
  void pausePreventsJobExecutionUntilResume() throws Exception {
    AgentVersion agentVersion = persistAgent();
    FlowDefinition definition = persistFlowDefinition(agentVersion.getId());

    Mockito.when(agentInvocationService.invoke(Mockito.any()))
        .thenReturn(
            new AgentInvocationResult(
                "Completed result",
                new UsageCostEstimate(
                    5,
                    3,
                    8,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    "USD",
                    com.aiadvent.backend.chat.provider.model.UsageSource.NATIVE),
                List.of()));

    FlowStartResponse startResponse = startFlow(definition.getId(), null);

    flowControlService.pause(startResponse.sessionId(), "operator");

    Optional<FlowJob> jobOptional = orchestratorService.processNextJob("worker-1");
    assertThat(jobOptional).isEmpty();
    Mockito.verify(agentInvocationService, Mockito.never()).invoke(Mockito.any());

    flowControlService.resume(startResponse.sessionId());

    orchestratorService.processNextJob("worker-1");
    Mockito.verify(agentInvocationService, Mockito.times(1)).invoke(Mockito.any());
  }

  private FlowStartResponse startFlow(UUID flowId, String body) throws Exception {
    var requestBuilder =
        post("/api/flows/" + flowId + "/start").contentType(MediaType.APPLICATION_JSON);
    if (body != null) {
      requestBuilder.content(body);
    }

    MvcResult result = mockMvc.perform(requestBuilder).andExpect(status().isOk()).andReturn();
    return objectMapper.readValue(result.getResponse().getContentAsString(), FlowStartResponse.class);
  }

  private AgentVersion persistAgent() {
    AgentDefinition definition = new AgentDefinition("agent-one", "Agent", null, true);
    agentDefinitionRepository.save(definition);
    AgentVersion version =
        new AgentVersion(
            definition,
            1,
            AgentVersionStatus.PUBLISHED,
            com.aiadvent.backend.chat.config.ChatProviderType.OPENAI,
            "openai",
            "gpt-4o-mini");
    return agentVersionRepository.save(version);
  }

  private FlowDefinition persistFlowDefinition(UUID agentVersionId) {
    ObjectNode root = objectMapper.createObjectNode();
    root.put("startStepId", "step-1");
    ArrayNode steps = root.putArray("steps");
    ObjectNode step = steps.addObject();
    step.put("id", "step-1");
    step.put("name", "Initial step");
    step.put("agentVersionId", agentVersionId.toString());
    step.put("prompt", "Solve the task");

    FlowDefinition definition =
        new FlowDefinition("simple-flow", 1, FlowDefinitionStatus.PUBLISHED, true, root);
    return flowDefinitionRepository.save(definition);
  }
}
