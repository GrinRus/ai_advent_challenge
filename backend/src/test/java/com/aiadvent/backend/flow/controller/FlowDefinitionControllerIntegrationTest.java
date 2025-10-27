package com.aiadvent.backend.flow.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aiadvent.backend.chat.config.ChatProviderType;
import com.aiadvent.backend.flow.TestAgentInvocationOptionsFactory;
import com.aiadvent.backend.flow.api.FlowDefinitionHistoryResponse;
import com.aiadvent.backend.flow.api.FlowDefinitionResponse;
import com.aiadvent.backend.flow.api.FlowDefinitionSummaryResponse;
import com.aiadvent.backend.flow.domain.AgentDefinition;
import com.aiadvent.backend.flow.domain.AgentVersion;
import com.aiadvent.backend.flow.domain.AgentVersionStatus;
import com.aiadvent.backend.flow.domain.FlowDefinitionStatus;
import com.aiadvent.backend.flow.persistence.AgentDefinitionRepository;
import com.aiadvent.backend.flow.persistence.AgentVersionRepository;
import com.aiadvent.backend.flow.persistence.FlowDefinitionHistoryRepository;
import com.aiadvent.backend.flow.persistence.FlowDefinitionRepository;
import com.aiadvent.backend.flow.persistence.FlowEventRepository;
import com.aiadvent.backend.flow.persistence.FlowSessionRepository;
import com.aiadvent.backend.flow.persistence.FlowJobRepository;
import com.aiadvent.backend.flow.persistence.FlowStepExecutionRepository;
import com.aiadvent.backend.support.PostgresTestContainer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FlowDefinitionControllerIntegrationTest extends PostgresTestContainer {

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private FlowDefinitionRepository flowDefinitionRepository;
  @Autowired private FlowDefinitionHistoryRepository flowDefinitionHistoryRepository;
  @Autowired private FlowEventRepository flowEventRepository;
  @Autowired private FlowStepExecutionRepository flowStepExecutionRepository;
  @Autowired private FlowSessionRepository flowSessionRepository;
  @Autowired private FlowJobRepository flowJobRepository;
  @Autowired private AgentDefinitionRepository agentDefinitionRepository;
  @Autowired private AgentVersionRepository agentVersionRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  @BeforeEach
  void clean() {
    jdbcTemplate.execute(
        "TRUNCATE TABLE flow_event, flow_step_execution, flow_job, flow_session, flow_definition_history, flow_definition, agent_capability, agent_version, agent_definition RESTART IDENTITY CASCADE");
  }

  @Test
  void createUpdatePublishAndFetchDefinitions() throws Exception {
    UUID agentVersionId = createAgentVersion();
    ObjectNode definitionBody = buildDefinitionBody(agentVersionId);

    // Create draft definition
    MvcResult createResult =
        mockMvc
            .perform(
                post("/api/flows/definitions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        objectMapper
                            .createObjectNode()
                            .put("name", "customer-onboarding")
                            .put("description", "Initial onboarding flow")
                            .put("updatedBy", "alice")
                            .set("definition", definitionBody)
                            .toString()))
            .andExpect(status().isCreated())
            .andReturn();

    FlowDefinitionResponse created =
        objectMapper.readValue(createResult.getResponse().getContentAsString(), FlowDefinitionResponse.class);

    assertThat(created.status()).isEqualTo(FlowDefinitionStatus.DRAFT);
    UUID definitionId = created.id();

    // Update draft with change notes
    definitionBody.put("title", "Updated onboarding flow");
    mockMvc
        .perform(
            put("/api/flows/definitions/" + definitionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper
                        .createObjectNode()
                        .put("description", "Second iteration")
                        .put("updatedBy", "bob")
                        .put("changeNotes", "Tweaked title")
                        .set("definition", definitionBody)
                        .toString()))
        .andExpect(status().isOk());

    // Publish definition
    mockMvc
        .perform(
            post("/api/flows/definitions/" + definitionId + "/publish")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper
                        .createObjectNode()
                        .put("updatedBy", "carol")
                        .put("changeNotes", "Ready for production")
                        .toString()))
        .andExpect(status().isOk());

    // List definitions
    MvcResult listResult =
        mockMvc
            .perform(get("/api/flows/definitions"))
            .andExpect(status().isOk())
            .andReturn();

    List<FlowDefinitionSummaryResponse> definitions =
        objectMapper.readValue(
            listResult.getResponse().getContentAsString(),
            objectMapper.getTypeFactory().constructCollectionType(List.class, FlowDefinitionSummaryResponse.class));

    assertThat(definitions).hasSize(1);
    FlowDefinitionSummaryResponse summary = definitions.get(0);
    assertThat(summary.status()).isEqualTo(FlowDefinitionStatus.PUBLISHED);
    assertThat(summary.active()).isTrue();

    // History endpoint
    MvcResult historyResult =
        mockMvc
            .perform(get("/api/flows/definitions/" + definitionId + "/history"))
            .andExpect(status().isOk())
            .andReturn();

    List<FlowDefinitionHistoryResponse> history =
        objectMapper.readValue(
            historyResult.getResponse().getContentAsString(),
            objectMapper.getTypeFactory().constructCollectionType(List.class, FlowDefinitionHistoryResponse.class));

    assertThat(history)
        .extracting(FlowDefinitionHistoryResponse::changeNotes)
        .contains("Tweaked title", "Ready for production");
  }

  private UUID createAgentVersion() {
    AgentDefinition agentDefinition =
        agentDefinitionRepository.save(
            new AgentDefinition("customer-support", "Customer Support", "Handles onboarding", true));
    AgentVersion agentVersion =
        new AgentVersion(
            agentDefinition,
            1,
            AgentVersionStatus.PUBLISHED,
            ChatProviderType.OPENAI,
            "openai",
            "gpt-4o-mini");
    agentVersion.setInvocationOptions(TestAgentInvocationOptionsFactory.minimal());
    agentVersion = agentVersionRepository.save(agentVersion);
    return agentVersion.getId();
  }

  private ObjectNode buildDefinitionBody(UUID agentVersionId) {
    ObjectNode root = objectMapper.createObjectNode();
    root.put("title", "Onboarding flow");
    root.put("startStepId", "step-1");
    ArrayNode steps = root.putArray("steps");
    ObjectNode step = steps.addObject();
    step.put("id", "step-1");
    step.put("name", "Gather requirements");
    step.put("agentVersionId", agentVersionId.toString());
    step.put("prompt", "Do something");
    step.putArray("memoryReads");
    step.putArray("memoryWrites");
    ObjectNode transitions = step.putObject("transitions");
    transitions.putObject("onSuccess").put("complete", true);
    transitions.putObject("onFailure").put("fail", true);
    return root;
  }
}
