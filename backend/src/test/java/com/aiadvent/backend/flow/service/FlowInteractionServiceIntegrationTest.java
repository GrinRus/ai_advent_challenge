package com.aiadvent.backend.flow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import com.aiadvent.backend.chat.config.ChatProviderType;
import com.aiadvent.backend.flow.config.FlowInteractionConfig;
import com.aiadvent.backend.flow.domain.AgentDefinition;
import com.aiadvent.backend.flow.domain.AgentVersion;
import com.aiadvent.backend.flow.domain.AgentVersionStatus;
import com.aiadvent.backend.flow.domain.FlowDefinition;
import com.aiadvent.backend.flow.domain.FlowDefinitionStatus;
import com.aiadvent.backend.flow.domain.FlowEvent;
import com.aiadvent.backend.flow.domain.FlowEventType;
import com.aiadvent.backend.flow.domain.FlowInteractionRequest;
import com.aiadvent.backend.flow.domain.FlowInteractionResponse;
import com.aiadvent.backend.flow.domain.FlowInteractionResponseSource;
import com.aiadvent.backend.flow.domain.FlowInteractionStatus;
import com.aiadvent.backend.flow.domain.FlowSession;
import com.aiadvent.backend.flow.domain.FlowSessionStatus;
import com.aiadvent.backend.flow.domain.FlowStepExecution;
import com.aiadvent.backend.flow.domain.FlowStepStatus;
import com.aiadvent.backend.flow.job.JobQueuePort;
import com.aiadvent.backend.flow.persistence.AgentDefinitionRepository;
import com.aiadvent.backend.flow.persistence.AgentVersionRepository;
import com.aiadvent.backend.flow.persistence.FlowDefinitionRepository;
import com.aiadvent.backend.flow.persistence.FlowEventRepository;
import com.aiadvent.backend.flow.persistence.FlowInteractionRequestRepository;
import com.aiadvent.backend.flow.persistence.FlowInteractionResponseRepository;
import com.aiadvent.backend.flow.persistence.FlowSessionRepository;
import com.aiadvent.backend.flow.persistence.FlowStepExecutionRepository;
import com.aiadvent.backend.flow.telemetry.FlowTelemetryService;
import com.aiadvent.backend.flow.validation.FlowInteractionSchemaValidator;
import com.aiadvent.backend.support.PostgresTestContainer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;

@org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
@org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase(
    replace = org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE)
@org.springframework.context.annotation.Import({
  FlowInteractionService.class,
  FlowInteractionSchemaValidator.class,
  SuggestedActionsSanitizer.class,
  FlowInteractionServiceIntegrationTest.TestConfig.class
})
class FlowInteractionServiceIntegrationTest extends PostgresTestContainer {

  @Autowired private FlowInteractionService flowInteractionService;
  @Autowired private FlowDefinitionRepository flowDefinitionRepository;
  @Autowired private FlowSessionRepository flowSessionRepository;
  @Autowired private FlowStepExecutionRepository flowStepExecutionRepository;
  @Autowired private FlowInteractionRequestRepository requestRepository;
  @Autowired private FlowInteractionResponseRepository responseRepository;
  @Autowired private FlowEventRepository flowEventRepository;
  @Autowired private AgentDefinitionRepository agentDefinitionRepository;
  @Autowired private AgentVersionRepository agentVersionRepository;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private FlowInteractionSchemaValidator schemaValidator;
  @Autowired private FlowTelemetryService telemetry;
  @Autowired private JobQueuePort jobQueuePort;

  @BeforeEach
  void cleanDatabase() {
    org.mockito.Mockito.reset(jobQueuePort, telemetry);
    responseRepository.deleteAll();
    requestRepository.deleteAll();
    flowEventRepository.deleteAll();
    flowStepExecutionRepository.deleteAll();
    flowSessionRepository.deleteAll();
    flowDefinitionRepository.deleteAll();
    agentVersionRepository.deleteAll();
    agentDefinitionRepository.deleteAll();
  }

  @Test
  void respondResumesStepAndEnqueuesJob() {
    FlowContext context = persistFlowContext();
    FlowInteractionRequest request =
        flowInteractionService.ensureRequest(
            context.session(),
            context.stepExecution(),
            defaultInteractionConfig(),
            context.agentVersion());

    ObjectNode payload = objectMapper.createObjectNode();
    payload.put("comment", "Ready to proceed");

    FlowInteractionResponse response =
        flowInteractionService.respond(
            context.session().getId(),
            request.getId(),
            context.session().getChatSessionId(),
            UUID.randomUUID(),
            payload,
            FlowInteractionResponseSource.USER,
            FlowInteractionStatus.ANSWERED);

    FlowInteractionRequest persistedRequest =
        requestRepository.findById(request.getId()).orElseThrow();
    FlowStepExecution stepExecution =
        flowStepExecutionRepository.findById(context.stepExecution().getId()).orElseThrow();
    FlowSession session = flowSessionRepository.findById(context.session().getId()).orElseThrow();

    assertThat(persistedRequest.getStatus()).isEqualTo(FlowInteractionStatus.ANSWERED);
    assertThat(stepExecution.getStatus()).isEqualTo(FlowStepStatus.PENDING);
    assertThat(stepExecution.getInteractionRequest()).isNull();
    assertThat(session.getStatus()).isEqualTo(FlowSessionStatus.RUNNING);
    assertThat(responseRepository.count()).isOne();

    JsonNode interactionNode = stepExecution.getInputPayload().get("interaction");
    assertThat(interactionNode.get("status").asText()).isEqualTo("ANSWERED");
    assertThat(interactionNode.get("payload").get("comment").asText()).isEqualTo("Ready to proceed");

    List<FlowEvent> events = flowEventRepository.findAll();
    assertThat(events)
        .extracting(FlowEvent::getEventType)
        .containsExactly(
            FlowEventType.HUMAN_INTERACTION_REQUIRED, FlowEventType.HUMAN_INTERACTION_RESPONDED);

    ArgumentCaptor<FlowSession> sessionCaptor = ArgumentCaptor.forClass(FlowSession.class);
    ArgumentCaptor<FlowStepExecution> stepCaptor = ArgumentCaptor.forClass(FlowStepExecution.class);
    ArgumentCaptor<com.aiadvent.backend.flow.job.FlowJobPayload> payloadCaptor =
        ArgumentCaptor.forClass(com.aiadvent.backend.flow.job.FlowJobPayload.class);
    verify(jobQueuePort)
        .enqueueStepJob(
            sessionCaptor.capture(),
            stepCaptor.capture(),
            payloadCaptor.capture(),
            org.mockito.Mockito.any(Instant.class));
    FlowSession enqueuedSession = sessionCaptor.getValue();
    FlowStepExecution enqueuedStep = stepCaptor.getValue();
    com.aiadvent.backend.flow.job.FlowJobPayload payloadValue = payloadCaptor.getValue();
    assertThat(enqueuedSession.getId()).isEqualTo(context.session().getId());
    assertThat(enqueuedStep.getId()).isEqualTo(stepExecution.getId());
    assertThat(payloadValue.flowSessionId()).isEqualTo(context.session().getId());
    assertThat(payloadValue.stepExecutionId()).isEqualTo(stepExecution.getId());
  }

  @Test
  void expireOverdueRequestsTransitionsToExpired() {
    FlowContext context = persistFlowContext();
    FlowInteractionRequest request =
        flowInteractionService.ensureRequest(
            context.session(),
            context.stepExecution(),
            defaultInteractionConfig(),
            context.agentVersion());

    request.setDueAt(Instant.now().minusSeconds(60));
    requestRepository.save(request);

    int processed =
        flowInteractionService.expireOverdueRequests(Instant.now().plusSeconds(5));

    assertThat(processed).isEqualTo(1);
    FlowInteractionRequest updatedRequest =
        requestRepository.findById(request.getId()).orElseThrow();
    FlowStepExecution stepExecution =
        flowStepExecutionRepository.findById(context.stepExecution().getId()).orElseThrow();
    FlowSession session = flowSessionRepository.findById(context.session().getId()).orElseThrow();

    assertThat(updatedRequest.getStatus()).isEqualTo(FlowInteractionStatus.EXPIRED);
    assertThat(stepExecution.getStatus()).isEqualTo(FlowStepStatus.PENDING);
    assertThat(session.getStatus()).isEqualTo(FlowSessionStatus.RUNNING);
    assertThat(responseRepository.count()).isOne();
    assertThat(flowEventRepository.findAll())
        .extracting(FlowEvent::getEventType)
        .contains(
            FlowEventType.HUMAN_INTERACTION_REQUIRED, FlowEventType.HUMAN_INTERACTION_EXPIRED);
    ArgumentCaptor<FlowSession> expireSessionCaptor = ArgumentCaptor.forClass(FlowSession.class);
    ArgumentCaptor<FlowStepExecution> expireStepCaptor =
        ArgumentCaptor.forClass(FlowStepExecution.class);
    ArgumentCaptor<com.aiadvent.backend.flow.job.FlowJobPayload> expirePayloadCaptor =
        ArgumentCaptor.forClass(com.aiadvent.backend.flow.job.FlowJobPayload.class);
    verify(jobQueuePort)
        .enqueueStepJob(
            expireSessionCaptor.capture(),
            expireStepCaptor.capture(),
            expirePayloadCaptor.capture(),
            org.mockito.Mockito.any(Instant.class));
    assertThat(expireSessionCaptor.getValue().getId()).isEqualTo(context.session().getId());
    assertThat(expireStepCaptor.getValue().getId()).isEqualTo(stepExecution.getId());
  }

  private FlowInteractionConfig defaultInteractionConfig() {
    ObjectNode schema = objectMapper.createObjectNode();
    schema.put("type", "object");
    ObjectNode properties = schema.putObject("properties");
    properties.putObject("comment").put("type", "string");
    ArrayNode required = schema.putArray("required");
    required.add("comment");
    schemaValidator.validateSchema(schema);
    return new FlowInteractionConfig(
        com.aiadvent.backend.flow.domain.FlowInteractionType.INPUT_FORM,
        "Need confirmation",
        "Please review",
        schema,
        objectMapper.createObjectNode(),
        5);
  }

  private FlowContext persistFlowContext() {
    FlowDefinition definition =
        flowDefinitionRepository.save(
            new FlowDefinition(
                "demo",
                1,
                FlowDefinitionStatus.PUBLISHED,
                true,
                objectMapper.createObjectNode()));

    FlowSession session =
        flowSessionRepository.save(
            new FlowSession(definition, 1, FlowSessionStatus.RUNNING, 0L, 0L));
    session.setChatSessionId(UUID.randomUUID());
    session.setCurrentStepId("collect");
    session = flowSessionRepository.save(session);

    FlowStepExecution stepExecution =
        flowStepExecutionRepository.save(
            new FlowStepExecution(session, "collect", FlowStepStatus.RUNNING, 1));

    AgentDefinition agentDefinition =
        agentDefinitionRepository.save(new AgentDefinition("agent", "Human Helper", null, true));
    AgentVersion agentVersion =
        agentVersionRepository.save(
            new AgentVersion(
                agentDefinition,
                1,
                AgentVersionStatus.PUBLISHED,
                ChatProviderType.OPENAI,
                "openai",
                "gpt-4o-mini"));

    stepExecution.setAgentVersion(agentVersion);
    stepExecution = flowStepExecutionRepository.save(stepExecution);

    return new FlowContext(session, stepExecution, agentVersion);
  }

  private record FlowContext(
      FlowSession session, FlowStepExecution stepExecution, AgentVersion agentVersion) {}

  @org.springframework.boot.test.context.TestConfiguration
  static class TestConfig {
    @org.springframework.context.annotation.Bean
    ObjectMapper objectMapper() {
      return new ObjectMapper();
    }

    @org.springframework.context.annotation.Bean
    FlowTelemetryService telemetry() {
      return org.mockito.Mockito.mock(FlowTelemetryService.class);
    }

    @org.springframework.context.annotation.Bean
    JobQueuePort jobQueuePort() {
      return org.mockito.Mockito.mock(JobQueuePort.class);
    }

  }
}
