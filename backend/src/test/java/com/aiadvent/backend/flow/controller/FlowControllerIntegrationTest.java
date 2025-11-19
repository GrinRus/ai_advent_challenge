package com.aiadvent.backend.flow.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aiadvent.backend.flow.TestAgentInvocationOptionsFactory;
import com.aiadvent.backend.flow.agent.options.AgentInvocationOptions;
import com.aiadvent.backend.flow.api.FlowEventDto;
import com.aiadvent.backend.flow.api.FlowStartResponse;
import com.aiadvent.backend.flow.domain.AgentDefinition;
import com.aiadvent.backend.flow.domain.AgentVersion;
import com.aiadvent.backend.flow.domain.AgentVersionStatus;
import com.aiadvent.backend.flow.blueprint.FlowBlueprint;
import com.aiadvent.backend.flow.domain.FlowDefinition;
import com.aiadvent.backend.flow.domain.FlowDefinitionStatus;
import com.aiadvent.backend.flow.domain.FlowJob;
import com.aiadvent.backend.flow.domain.FlowEvent;
import com.aiadvent.backend.flow.domain.FlowEventType;
import com.aiadvent.backend.flow.domain.FlowSession;
import com.aiadvent.backend.flow.domain.FlowSessionStatus;
import com.aiadvent.backend.flow.domain.FlowStepExecution;
import com.aiadvent.backend.flow.domain.FlowStepStatus;
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
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(
    properties = {
      "spring.ai.openai.api-key=test-token",
      "spring.ai.openai.base-url=http://localhost",
      "app.chat.default-provider=stub",
      "app.flow.worker.enabled=false"
    })
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
class FlowControllerIntegrationTest extends PostgresTestContainer {

  private static final Duration JOB_PROCESS_RETRY_DELAY = Duration.ofMillis(200);
  private static final int JOB_PROCESS_MAX_ATTEMPTS = 10;
  private static final String TEST_WORKER_ID = "test-worker";

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
    jdbcTemplate.execute(
        "TRUNCATE TABLE flow_event, flow_step_execution, flow_job, flow_session, flow_definition_history, flow_definition, agent_capability, agent_version, agent_definition RESTART IDENTITY CASCADE");
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
                List.of(),
                null,
                null,
                null,
                List.of(),
                null,
                List.of(),
                null));

    FlowStartResponse startResponse = startFlow(definition.getId(), null);

    processNextJobWithRetry();

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
                List.of(),
                null,
                null,
                null,
                List.of(),
                null,
                List.of(),
                null));

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

    assertThat(startResponse.launchParameters().isEmpty()).isFalse();
    assertThat(startResponse.overrides()).isNotNull();
    assertThat(startResponse.overrides().temperature()).isEqualTo(0.2d);
    assertThat(startResponse.sharedContext()).isNotNull();
    assertThat(startResponse.sharedContext().asJson().get("initial")).isNotNull();

    processNextJobWithRetry();

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

  private void processNextJobWithRetry() {
    Optional<FlowJob> jobOptional = processNextJobSafely(true);
    if (jobOptional.isEmpty()) {
      fail("Flow job was not processed after multiple attempts");
    }
  }

  private Optional<FlowJob> attemptProcessNextJob() {
    return processNextJobSafely(false);
  }

  private Optional<FlowJob> processNextJobSafely(boolean requireJob) {
    RuntimeException lastException = null;
    Optional<FlowJob> jobOptional = Optional.empty();
    for (int attempt = 0; attempt < JOB_PROCESS_MAX_ATTEMPTS; attempt++) {
      try {
        jobOptional = orchestratorService.processNextJob(TEST_WORKER_ID);
        if (jobOptional.isPresent() || !requireJob) {
          return jobOptional;
        }
      } catch (RuntimeException exception) {
        lastException = exception;
      }
      try {
        TimeUnit.MILLISECONDS.sleep(JOB_PROCESS_RETRY_DELAY.toMillis());
      } catch (InterruptedException interruptedException) {
        Thread.currentThread().interrupt();
        fail("Interrupted while waiting for flow job processing");
      }
    }
    if (lastException != null) {
      throw lastException;
    }
    return jobOptional;
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
                List.of(),
                null,
                null,
                null,
                List.of(),
                null,
                List.of(),
                null));

    FlowStartResponse startResponse = startFlow(definition.getId(), null);

    flowControlService.pause(startResponse.sessionId(), "operator");

    Optional<FlowJob> jobOptional = attemptProcessNextJob();
    assertThat(jobOptional).isEmpty();
    Mockito.verify(agentInvocationService, Mockito.never()).invoke(Mockito.any());

    flowControlService.resume(startResponse.sessionId());

    processNextJobWithRetry();
    Mockito.verify(agentInvocationService, Mockito.times(1)).invoke(Mockito.any());
  }

  
  @Test
  void manualApprovalRetryResumesFlow() throws Exception {
    AgentVersion agentVersion = persistAgent();
    FlowDefinition definition = persistFlowDefinitionWithApproval(agentVersion.getId());

    Mockito.when(agentInvocationService.invoke(Mockito.any()))
        .thenThrow(new RuntimeException("boom"))
        .thenReturn(
            new AgentInvocationResult(
                "Recovered",
                new UsageCostEstimate(
                    5,
                    2,
                    7,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    "USD",
                    com.aiadvent.backend.chat.provider.model.UsageSource.NATIVE),
                List.of(),
                null,
                null,
                null,
                List.of(),
                null,
                List.of(),
                null));

    FlowStartResponse startResponse = startFlow(definition.getId(), null);

    processNextJobWithRetry();

    FlowSession waiting =
        flowSessionRepository.findById(startResponse.sessionId()).orElseThrow();
    assertThat(waiting.getStatus()).isEqualTo(FlowSessionStatus.WAITING_STEP_APPROVAL);

    FlowStepExecution failedExecution =
        findLatestStepExecution(startResponse.sessionId(), FlowStepStatus.WAITING_APPROVAL);

    ObjectNode approvePayload = objectMapper.createObjectNode();
    approvePayload.put("command", "approveStep");
    approvePayload.put("stepExecutionId", failedExecution.getId().toString());

    mockMvc
        .perform(
            post("/api/flows/" + startResponse.sessionId() + "/control")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(approvePayload)))
        .andExpect(status().isOk());

    processNextJobWithRetry();

    FlowSession completed =
        flowSessionRepository.findById(startResponse.sessionId()).orElseThrow();
    assertThat(completed.getStatus()).isEqualTo(FlowSessionStatus.COMPLETED);
  }

  @Test
  void manualSkipSchedulesFallbackStep() throws Exception {
    AgentVersion agentVersion = persistAgent();
    FlowDefinition definition = persistFlowDefinitionWithFallback(agentVersion.getId());

    Mockito.when(agentInvocationService.invoke(Mockito.any()))
        .thenThrow(new RuntimeException("boom"))
        .thenReturn(
            new AgentInvocationResult(
                "Fallback",
                new UsageCostEstimate(
                    4,
                    2,
                    6,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    "USD",
                    com.aiadvent.backend.chat.provider.model.UsageSource.NATIVE),
                List.of(),
                null,
                null,
                null,
                List.of(),
                null,
                List.of(),
                null));

    FlowStartResponse startResponse = startFlow(definition.getId(), null);

    processNextJobWithRetry();

    FlowSession waiting =
        flowSessionRepository.findById(startResponse.sessionId()).orElseThrow();
    assertThat(waiting.getStatus()).isEqualTo(FlowSessionStatus.WAITING_STEP_APPROVAL);

    FlowStepExecution failedExecution =
        findLatestStepExecution(startResponse.sessionId(), FlowStepStatus.WAITING_APPROVAL);

    ObjectNode skipPayload = objectMapper.createObjectNode();
    skipPayload.put("command", "skipStep");
    skipPayload.put("stepExecutionId", failedExecution.getId().toString());

    mockMvc
        .perform(
            post("/api/flows/" + startResponse.sessionId() + "/control")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(skipPayload)))
        .andExpect(status().isOk());

    processNextJobWithRetry();
    processNextJobWithRetry();

    FlowSession completed =
        flowSessionRepository.findById(startResponse.sessionId()).orElseThrow();
    assertThat(completed.getStatus()).isEqualTo(FlowSessionStatus.COMPLETED);
    assertThat(completed.getCurrentStepId()).isEqualTo("finish");
  }

  @Test
  void githubGradleTestFlowRunsToCompletion() throws Exception {
    AgentVersion fetchAgent =
        persistAgent(
            "repo-fetcher-test",
            optionsWithToolBindings(List.of(toolBinding("github.repository_fetch"))));
    AgentVersion navigatorAgent =
        persistAgent(
            "workspace-navigator-test",
            optionsWithToolBindings(List.of(toolBinding("github.workspace_directory_inspector"))));
    AgentVersion gradleAgent =
        persistAgent(
            "gradle-test-runner-test",
            optionsWithToolBindings(List.of(toolBinding("docker.build_runner"))));

    FlowDefinition definition =
        persistGithubGradleTestFlow(
            fetchAgent.getId(), navigatorAgent.getId(), gradleAgent.getId());

    Mockito.when(agentInvocationService.invoke(Mockito.any()))
        .thenReturn(successfulInvocation("Fetch complete"))
        .thenReturn(successfulInvocation("Inspection complete"))
        .thenReturn(successfulInvocation("Gradle execution complete"));

    ObjectNode request = objectMapper.createObjectNode();
    ObjectNode parameters = request.putObject("parameters");
    parameters.put("repositoryUrl", "https://github.com/example/project");
    parameters.put("ref", "main");
    parameters.putArray("tasks").add("test");

    FlowStartResponse startResponse =
        startFlow(definition.getId(), objectMapper.writeValueAsString(request));

    processNextJobWithRetry();
    processNextJobWithRetry();
    processNextJobWithRetry();

    FlowSession session =
        flowSessionRepository.findById(startResponse.sessionId()).orElseThrow();
    assertThat(session.getStatus()).isEqualTo(FlowSessionStatus.COMPLETED);

    Mockito.verify(agentInvocationService, Mockito.times(3)).invoke(Mockito.any());

    MvcResult snapshotResult =
        mockMvc
            .perform(get("/api/flows/" + startResponse.sessionId() + "/snapshot"))
            .andExpect(status().isOk())
            .andReturn();
    FlowStatusResponse statusResponse =
        objectMapper.readValue(snapshotResult.getResponse().getContentAsString(), FlowStatusResponse.class);
    long completedSteps =
        statusResponse.events().stream()
            .filter(event -> event.type() == FlowEventType.STEP_COMPLETED)
            .count();
    assertThat(completedSteps).isEqualTo(3);
  }

  @Test
  void githubGradleTestFlowEmitsProgressEvents() throws Exception {
    FlowDefinition definition = prepareGithubGradleFlow();

    Mockito.when(agentInvocationService.invoke(Mockito.any()))
        .thenReturn(successfulInvocation("Fetch complete"))
        .thenReturn(successfulInvocation("Inspection complete"))
        .thenReturn(successfulInvocation("Gradle execution complete"));

    FlowStartResponse startResponse =
        startFlow(definition.getId(), objectMapper.writeValueAsString(gradleFlowRequest()));

    processNextJobWithRetry();
    processNextJobWithRetry();
    processNextJobWithRetry();

    FlowStatusResponse snapshot = snapshot(startResponse.sessionId());

    assertThat(extractStepField(snapshot.events(), FlowEventType.STEP_STARTED, "phase"))
        .containsExactly("fetching", "inspecting_workspace", "running_tests");
    assertThat(extractStepField(snapshot.events(), FlowEventType.STEP_COMPLETED, "stepId"))
        .containsExactly("fetch_workspace", "inspect_workspace", "run_gradle_tests");
    assertThat(snapshot.events())
        .filteredOn(event -> event.type() == FlowEventType.FLOW_COMPLETED)
        .hasSize(1);
  }

  @Test
  void githubGradleTestFlowFailsWhenGradleStepErrors() throws Exception {
    FlowDefinition definition = prepareGithubGradleFlow();

    Mockito.when(agentInvocationService.invoke(Mockito.any()))
        .thenReturn(successfulInvocation("Fetch complete"))
        .thenReturn(successfulInvocation("Inspection complete"))
        .thenThrow(new RuntimeException("Gradle failed"));

    FlowStartResponse startResponse =
        startFlow(definition.getId(), objectMapper.writeValueAsString(gradleFlowRequest()));

    processNextJobWithRetry();
    processNextJobWithRetry();
    processNextJobWithRetry();

    FlowSession session =
        flowSessionRepository.findById(startResponse.sessionId()).orElseThrow();
    assertThat(session.getStatus()).isEqualTo(FlowSessionStatus.FAILED);

    FlowStatusResponse snapshot = snapshot(startResponse.sessionId());
    assertThat(snapshot.events())
        .filteredOn(event -> event.type() == FlowEventType.STEP_FAILED)
        .hasSize(1);
    assertThat(snapshot.events())
        .filteredOn(event -> event.type() == FlowEventType.FLOW_FAILED)
        .hasSize(1);
    FlowEventDto failedEvent =
        snapshot.events().stream()
            .filter(event -> event.type() == FlowEventType.STEP_FAILED)
            .findFirst()
            .orElseThrow();
    JsonNode failedPayload = payloadOf(failedEvent);
    assertThat(failedPayload.path("context").path("launchParameters").path("repositoryUrl").asText())
        .isEqualTo("https://github.com/example/project");
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

  private AgentInvocationResult successfulInvocation(String output) {
    return new AgentInvocationResult(
        output,
        new UsageCostEstimate(
            1,
            1,
            2,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            "USD",
            com.aiadvent.backend.chat.provider.model.UsageSource.NATIVE),
        List.of(),
        null,
        null,
        null,
        List.of(),
        null,
        List.of(),
        null);
  }

  private FlowDefinition prepareGithubGradleFlow() {
    AgentVersion fetchAgent =
        persistAgent(
            "repo-fetcher-test",
            optionsWithToolBindings(List.of(toolBinding("github.repository_fetch"))));
    AgentVersion navigatorAgent =
        persistAgent(
            "workspace-navigator-test",
            optionsWithToolBindings(List.of(toolBinding("github.workspace_directory_inspector"))));
    AgentVersion gradleAgent =
        persistAgent(
            "gradle-test-runner-test",
            optionsWithToolBindings(List.of(toolBinding("docker.build_runner"))));

    return persistGithubGradleTestFlow(
        fetchAgent.getId(), navigatorAgent.getId(), gradleAgent.getId());
  }

  private ObjectNode gradleFlowRequest() {
    ObjectNode request = objectMapper.createObjectNode();
    ObjectNode parameters = request.putObject("parameters");
    parameters.put("repositoryUrl", "https://github.com/example/project");
    parameters.put("ref", "main");
    parameters.putArray("tasks").add("test");
    return request;
  }

  private FlowStatusResponse snapshot(UUID sessionId) throws Exception {
    MvcResult snapshotResult =
        mockMvc
            .perform(get("/api/flows/" + sessionId + "/snapshot"))
            .andExpect(status().isOk())
            .andReturn();
    return objectMapper.readValue(snapshotResult.getResponse().getContentAsString(), FlowStatusResponse.class);
  }

  private JsonNode payloadOf(FlowEventDto event) {
    return objectMapper.valueToTree(event.payload());
  }

  private List<String> extractStepField(
      List<FlowEventDto> events, FlowEventType type, String fieldName) {
    return events.stream()
        .filter(event -> event.type() == type)
        .map(this::payloadOf)
        .map(payload -> payload.path("step").path(fieldName).asText(null))
        .collect(Collectors.toList());
  }

  private AgentInvocationOptions optionsWithToolBindings(
      List<AgentInvocationOptions.ToolBinding> bindings) {
    AgentInvocationOptions base = TestAgentInvocationOptionsFactory.minimal();
    return new AgentInvocationOptions(
        base.provider(),
        base.prompt(),
        base.memoryPolicy(),
        base.retryPolicy(),
        base.advisorSettings(),
        new AgentInvocationOptions.Tooling(bindings),
        base.costProfile());
  }

  private AgentInvocationOptions.ToolBinding toolBinding(String toolCode) {
    return new AgentInvocationOptions.ToolBinding(
        toolCode, 1, AgentInvocationOptions.ExecutionMode.AUTO, null, null);
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
    version.setInvocationOptions(TestAgentInvocationOptionsFactory.minimal());
    return agentVersionRepository.save(version);
  }

  private AgentVersion persistAgent(
      String identifier, AgentInvocationOptions invocationOptions) {
    AgentDefinition definition = new AgentDefinition(identifier, identifier, null, true);
    agentDefinitionRepository.save(definition);
    AgentVersion version =
        new AgentVersion(
            definition,
            1,
            AgentVersionStatus.PUBLISHED,
            com.aiadvent.backend.chat.config.ChatProviderType.OPENAI,
            "openai",
            "gpt-4o-mini");
    version.setInvocationOptions(invocationOptions);
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
        new FlowDefinition("simple-flow", 1, FlowDefinitionStatus.PUBLISHED, true, toBlueprint(root));
    return flowDefinitionRepository.save(definition);
  }

  private FlowDefinition persistFlowDefinitionWithApproval(UUID agentVersionId) {
    ObjectNode root = objectMapper.createObjectNode();
    root.put("startStepId", "step-1");
    ArrayNode steps = root.putArray("steps");
    ObjectNode step = steps.addObject();
    step.put("id", "step-1");
    step.put("name", "Initial step");
    step.put("agentVersionId", agentVersionId.toString());
    step.put("prompt", "Solve the task");
    ObjectNode transitions = step.putObject("transitions");
    transitions.putObject("onSuccess").put("complete", true);
    transitions.putObject("onFailure").put("fail", false);

    FlowDefinition definition =
        new FlowDefinition("approval-flow", 1, FlowDefinitionStatus.PUBLISHED, true, toBlueprint(root));
    return flowDefinitionRepository.save(definition);
  }

  private FlowDefinition persistFlowDefinitionWithFallback(UUID agentVersionId) {
    ObjectNode root = objectMapper.createObjectNode();
    root.put("startStepId", "primary");
    ArrayNode steps = root.putArray("steps");

    ObjectNode primary = steps.addObject();
    primary.put("id", "primary");
    primary.put("name", "Primary");
    primary.put("agentVersionId", agentVersionId.toString());
    primary.put("prompt", "Do primary work");
    ObjectNode primaryTransitions = primary.putObject("transitions");
    ObjectNode onSuccess = primaryTransitions.putObject("onSuccess");
    onSuccess.put("next", "finish");
    ObjectNode onFailure = primaryTransitions.putObject("onFailure");
    onFailure.put("next", "fallback");
    onFailure.put("fail", false);

    ObjectNode fallback = steps.addObject();
    fallback.put("id", "fallback");
    fallback.put("name", "Fallback");
    fallback.put("agentVersionId", agentVersionId.toString());
    fallback.put("prompt", "Handle failure");
    ObjectNode fallbackTransitions = fallback.putObject("transitions");
    fallbackTransitions.putObject("onSuccess").put("next", "finish");

    ObjectNode finish = steps.addObject();
    finish.put("id", "finish");
    finish.put("name", "Finish");
    finish.put("agentVersionId", agentVersionId.toString());
    finish.putObject("transitions").putObject("onSuccess").put("complete", true);

    FlowDefinition definition =
        new FlowDefinition("fallback-flow", 1, FlowDefinitionStatus.PUBLISHED, true, toBlueprint(root));
    return flowDefinitionRepository.save(definition);
  }

  private FlowDefinition persistGithubGradleTestFlow(
      UUID fetchAgentVersion, UUID inspectorAgentVersion, UUID runnerAgentVersion) {
    ObjectNode root = objectMapper.createObjectNode();
    root.put("startStepId", "fetch_workspace");
    ArrayNode steps = root.putArray("steps");

    ObjectNode fetchStep = steps.addObject();
    fetchStep.put("id", "fetch_workspace");
    fetchStep.put("name", "Fetch workspace");
    fetchStep.put("agentVersionId", fetchAgentVersion.toString());
    fetchStep.put("prompt", "Prepare workspace for testing");
    fetchStep.putObject("transitions").putObject("onSuccess").put("next", "inspect_workspace");

    ObjectNode inspectStep = steps.addObject();
    inspectStep.put("id", "inspect_workspace");
    inspectStep.put("name", "Inspect workspace");
    inspectStep.put("agentVersionId", inspectorAgentVersion.toString());
    inspectStep.put("prompt", "Suggest Gradle project path");
    inspectStep.putObject("transitions").putObject("onSuccess").put("next", "run_gradle_tests");

    ObjectNode runStep = steps.addObject();
    runStep.put("id", "run_gradle_tests");
    runStep.put("name", "Run Gradle tests");
    runStep.put("agentVersionId", runnerAgentVersion.toString());
    runStep.put("prompt", "Execute Gradle tasks in Docker");
    runStep.putObject("transitions").putObject("onSuccess").put("complete", true);

    FlowDefinition definition =
        new FlowDefinition(
            "github-gradle-test-flow",
            1,
            FlowDefinitionStatus.PUBLISHED,
            true,
            toBlueprint(root));
    return flowDefinitionRepository.save(definition);
  }

  private FlowStepExecution findLatestStepExecution(UUID sessionId, FlowStepStatus status) {
    return flowStepExecutionRepository.findAll().stream()
        .filter(execution ->
            execution.getFlowSession().getId().equals(sessionId)
                && execution.getStatus() == status)
        .findFirst()
        .orElseThrow();
  }

  private FlowBlueprint toBlueprint(ObjectNode root) {
    try {
      return objectMapper
          .copy()
          .configure(com.fasterxml.jackson.databind.MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS, true)
          .treeToValue(root, FlowBlueprint.class);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Failed to build blueprint for test", exception);
    }
  }
}
