package com.aiadvent.backend.flow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aiadvent.backend.flow.config.FlowInteractionConfig;
import com.aiadvent.backend.flow.domain.AgentDefinition;
import com.aiadvent.backend.flow.domain.AgentVersion;
import com.aiadvent.backend.flow.domain.AgentVersionStatus;
import com.aiadvent.backend.flow.domain.FlowEvent;
import com.aiadvent.backend.flow.domain.FlowInteractionRequest;
import com.aiadvent.backend.flow.domain.FlowInteractionResponse;
import com.aiadvent.backend.flow.domain.FlowInteractionResponseSource;
import com.aiadvent.backend.flow.domain.FlowInteractionStatus;
import com.aiadvent.backend.flow.domain.FlowSession;
import com.aiadvent.backend.flow.domain.FlowSessionStatus;
import com.aiadvent.backend.flow.domain.FlowStepExecution;
import com.aiadvent.backend.flow.domain.FlowStepStatus;
import com.aiadvent.backend.flow.job.FlowJobPayload;
import com.aiadvent.backend.flow.job.JobQueuePort;
import com.aiadvent.backend.flow.persistence.FlowEventRepository;
import com.aiadvent.backend.flow.persistence.FlowInteractionRequestRepository;
import com.aiadvent.backend.flow.persistence.FlowInteractionResponseRepository;
import com.aiadvent.backend.flow.persistence.FlowSessionRepository;
import com.aiadvent.backend.flow.persistence.FlowStepExecutionRepository;
import com.aiadvent.backend.flow.telemetry.FlowTelemetryService;
import com.aiadvent.backend.flow.validation.FlowInteractionSchemaValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FlowInteractionServiceTest {

  private FlowInteractionRequestRepository requestRepository;
  private FlowInteractionResponseRepository responseRepository;
  private FlowSessionRepository flowSessionRepository;
  private FlowStepExecutionRepository flowStepExecutionRepository;
  private FlowEventRepository flowEventRepository;
  private JobQueuePort jobQueuePort;
  private FlowTelemetryService telemetry;
  private ObjectMapper objectMapper;
  private FlowInteractionSchemaValidator schemaValidator;

  private FlowInteractionService flowInteractionService;

  @BeforeEach
  void setUp() {
    requestRepository = mock(FlowInteractionRequestRepository.class);
    responseRepository = mock(FlowInteractionResponseRepository.class);
    flowSessionRepository = mock(FlowSessionRepository.class);
    flowStepExecutionRepository = mock(FlowStepExecutionRepository.class);
    flowEventRepository = mock(FlowEventRepository.class);
    jobQueuePort = mock(JobQueuePort.class);
    telemetry = mock(FlowTelemetryService.class);
    objectMapper = new ObjectMapper();
    schemaValidator = new FlowInteractionSchemaValidator();

    flowInteractionService =
        new FlowInteractionService(
            requestRepository,
            responseRepository,
            flowSessionRepository,
            flowStepExecutionRepository,
            flowEventRepository,
            jobQueuePort,
            telemetry,
            objectMapper,
            schemaValidator);
  }

  @Test
  void ensureRequestCreatesPendingRequestAndUpdatesState() {
    FlowSession session =
        new FlowSession(mock(com.aiadvent.backend.flow.domain.FlowDefinition.class), 1, FlowSessionStatus.RUNNING, 0L, 0L);
    UUID sessionId = UUID.randomUUID();
    setField(session, "id", sessionId);
    UUID chatSessionId = UUID.randomUUID();
    session.setChatSessionId(chatSessionId);

    FlowStepExecution stepExecution =
        new FlowStepExecution(session, "collect", FlowStepStatus.PENDING, 1);
    UUID stepExecutionId = UUID.randomUUID();
    setField(stepExecution, "id", stepExecutionId);

    AgentVersion agentVersion =
        new AgentVersion(
            new AgentDefinition("agent", "Agent", null, true),
            1,
            AgentVersionStatus.PUBLISHED,
            com.aiadvent.backend.chat.config.ChatProviderType.OPENAI,
            "openai",
            "gpt-4o-mini");
    setField(agentVersion, "id", UUID.randomUUID());

    FlowInteractionConfig config =
        new FlowInteractionConfig(
            com.aiadvent.backend.flow.domain.FlowInteractionType.INPUT_FORM,
            "Need details",
            "Provide missing info",
            objectMapper.createObjectNode(),
            objectMapper.createArrayNode(),
            15);

    when(requestRepository.findByFlowStepExecutionId(stepExecutionId)).thenReturn(Optional.empty());
    doAnswer(invocation -> invocation.getArgument(0))
        .when(flowSessionRepository)
        .save(any(FlowSession.class));
    doAnswer(invocation -> invocation.getArgument(0))
        .when(flowStepExecutionRepository)
        .save(any(FlowStepExecution.class));
    doAnswer(invocation -> {
          FlowInteractionRequest saved = invocation.getArgument(0);
          setField(saved, "id", UUID.randomUUID());
          return saved;
        })
        .when(requestRepository)
        .save(any(FlowInteractionRequest.class));
    when(flowEventRepository.save(any(FlowEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));

    FlowInteractionRequest request =
        flowInteractionService.ensureRequest(session, stepExecution, config, agentVersion);

    assertThat(request.getStatus()).isEqualTo(FlowInteractionStatus.PENDING);
    assertThat(request.getChatSessionId()).isEqualTo(chatSessionId);
    assertThat(request.getDueAt()).isNotNull();
    assertThat(session.getStatus()).isEqualTo(FlowSessionStatus.WAITING_USER_INPUT);
    assertThat(stepExecution.getStatus()).isEqualTo(FlowStepStatus.WAITING_USER_INPUT);
    verify(flowEventRepository).save(any(FlowEvent.class));
    verify(telemetry).interactionCreated(request.getId());
  }

  @Test
  void respondResumesSessionAndSchedulesJob() {
    FlowSession session =
        new FlowSession(mock(com.aiadvent.backend.flow.domain.FlowDefinition.class), 1, FlowSessionStatus.WAITING_USER_INPUT, 0L, 0L);
    UUID sessionId = UUID.randomUUID();
    setField(session, "id", sessionId);

    FlowStepExecution stepExecution =
        new FlowStepExecution(session, "collect", FlowStepStatus.WAITING_USER_INPUT, 1);
    UUID stepExecutionId = UUID.randomUUID();
    setField(stepExecution, "id", stepExecutionId);

    FlowInteractionRequest request =
        new FlowInteractionRequest(
            session,
            stepExecution,
            UUID.randomUUID(),
            new AgentVersion(
                new AgentDefinition("agent", "Agent", null, true),
                1,
                AgentVersionStatus.PUBLISHED,
                com.aiadvent.backend.chat.config.ChatProviderType.OPENAI,
                "openai",
                "gpt-4o-mini"),
            com.aiadvent.backend.flow.domain.FlowInteractionType.INPUT_FORM,
            FlowInteractionStatus.PENDING);
    setField(request, "id", UUID.randomUUID());

    when(requestRepository.findById(request.getId())).thenReturn(Optional.of(request));
    when(responseRepository.existsByRequest(request)).thenReturn(false);
    doAnswer(invocation -> invocation.getArgument(0))
        .when(flowSessionRepository)
        .save(any(FlowSession.class));
    doAnswer(invocation -> invocation.getArgument(0))
        .when(flowStepExecutionRepository)
        .save(any(FlowStepExecution.class));
    when(responseRepository.save(any(FlowInteractionResponse.class)))
        .thenAnswer(invocation -> {
          FlowInteractionResponse response = invocation.getArgument(0);
          setField(response, "id", UUID.randomUUID());
          return response;
        });

    flowInteractionService.respond(
        sessionId,
        request.getId(),
        request.getChatSessionId(),
        UUID.randomUUID(),
        objectMapper.createObjectNode().put("field", "value"),
        FlowInteractionResponseSource.USER,
        FlowInteractionStatus.ANSWERED);

    assertThat(request.getStatus()).isEqualTo(FlowInteractionStatus.ANSWERED);
    assertThat(session.getStatus()).isEqualTo(FlowSessionStatus.RUNNING);
    assertThat(stepExecution.getStatus()).isEqualTo(FlowStepStatus.PENDING);
    verify(jobQueuePort)
        .enqueueStepJob(any(FlowSession.class), any(FlowStepExecution.class), any(FlowJobPayload.class), any(Instant.class));
    verify(telemetry).interactionResolved(eq(request.getId()), eq(FlowInteractionStatus.ANSWERED));
  }

  @Test
  void autoResolvePendingRequestsWithoutResumeDoesNotScheduleJobs() {
    FlowSession session =
        new FlowSession(mock(com.aiadvent.backend.flow.domain.FlowDefinition.class), 1, FlowSessionStatus.CANCELLED, 0L, 0L);
    setField(session, "id", UUID.randomUUID());

    FlowStepExecution execution =
        new FlowStepExecution(session, "collect", FlowStepStatus.WAITING_USER_INPUT, 1);
    setField(execution, "id", UUID.randomUUID());

    FlowInteractionRequest request =
        new FlowInteractionRequest(
            session,
            execution,
            UUID.randomUUID(),
            new AgentVersion(
                new AgentDefinition("agent", "Agent", null, true),
                1,
                AgentVersionStatus.PUBLISHED,
                com.aiadvent.backend.chat.config.ChatProviderType.OPENAI,
                "openai",
                "gpt-4o-mini"),
            com.aiadvent.backend.flow.domain.FlowInteractionType.INPUT_FORM,
            FlowInteractionStatus.PENDING);
    setField(request, "id", UUID.randomUUID());

    when(requestRepository.findByFlowSessionAndStatus(session, FlowInteractionStatus.PENDING))
        .thenReturn(java.util.List.of(request));
    when(requestRepository.findById(request.getId())).thenReturn(Optional.of(request));
    when(responseRepository.save(any(FlowInteractionResponse.class)))
        .thenAnswer(invocation -> {
          FlowInteractionResponse response = invocation.getArgument(0);
          setField(response, "id", UUID.randomUUID());
          return response;
        });
    when(flowEventRepository.save(any(FlowEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));
    doAnswer(invocation -> invocation.getArgument(0))
        .when(flowStepExecutionRepository)
        .save(any(FlowStepExecution.class));

    flowInteractionService.autoResolvePendingRequests(
        session, FlowInteractionResponseSource.SYSTEM, null, false);

    assertThat(request.getStatus()).isEqualTo(FlowInteractionStatus.AUTO_RESOLVED);
    assertThat(execution.getStatus()).isEqualTo(FlowStepStatus.CANCELLED);
    verify(jobQueuePort, never())
        .enqueueStepJob(any(FlowSession.class), any(FlowStepExecution.class), any(FlowJobPayload.class), any(Instant.class));
    verify(telemetry).interactionResolved(eq(request.getId()), eq(FlowInteractionStatus.AUTO_RESOLVED));
  }

  @Test
  void respondWithInvalidPayloadThrows() {
    FlowSession session =
        new FlowSession(mock(com.aiadvent.backend.flow.domain.FlowDefinition.class), 1, FlowSessionStatus.WAITING_USER_INPUT, 0L, 0L);
    setField(session, "id", UUID.randomUUID());

    FlowStepExecution execution =
        new FlowStepExecution(session, "collect", FlowStepStatus.WAITING_USER_INPUT, 1);
    setField(execution, "id", UUID.randomUUID());

    FlowInteractionRequest request =
        new FlowInteractionRequest(
            session,
            execution,
            UUID.randomUUID(),
            new AgentVersion(
                new AgentDefinition("agent", "Agent", null, true),
                1,
                AgentVersionStatus.PUBLISHED,
                com.aiadvent.backend.chat.config.ChatProviderType.OPENAI,
                "openai",
                "gpt-4o-mini"),
            com.aiadvent.backend.flow.domain.FlowInteractionType.INPUT_FORM,
            FlowInteractionStatus.PENDING);
    setField(request, "id", UUID.randomUUID());

    ObjectNode schema = objectMapper.createObjectNode();
    schema.put("type", "object");
    ObjectNode props = schema.putObject("properties");
    props.putObject("count").put("type", "number").put("minimum", 0);
    schema.putArray("required").add("count");
    request.setPayloadSchema(schema);

    when(requestRepository.findById(request.getId())).thenReturn(Optional.of(request));
    when(responseRepository.existsByRequest(request)).thenReturn(false);

    assertThatThrownBy(
            () ->
                flowInteractionService.respond(
                    session.getId(),
                    request.getId(),
                    request.getChatSessionId(),
                    UUID.randomUUID(),
                    objectMapper.createObjectNode().put("count", "oops"),
                    FlowInteractionResponseSource.USER,
                    FlowInteractionStatus.ANSWERED))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("count");

    verify(jobQueuePort, never())
        .enqueueStepJob(any(FlowSession.class), any(FlowStepExecution.class), any(FlowJobPayload.class), any(Instant.class));
    verify(telemetry, never()).interactionResolved(eq(request.getId()), any(FlowInteractionStatus.class));
  }

  private static void setField(Object target, String fieldName, Object value) {
    try {
      java.lang.reflect.Field field = target.getClass().getDeclaredField(fieldName);
      field.setAccessible(true);
      field.set(target, value);
    } catch (Exception exception) {
      throw new RuntimeException(exception);
    }
  }
}
