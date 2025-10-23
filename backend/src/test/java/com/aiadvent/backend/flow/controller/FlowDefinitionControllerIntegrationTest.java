package com.aiadvent.backend.flow.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aiadvent.backend.flow.api.FlowDefinitionHistoryResponse;
import com.aiadvent.backend.flow.api.FlowDefinitionResponse;
import com.aiadvent.backend.flow.api.FlowDefinitionSummaryResponse;
import com.aiadvent.backend.flow.domain.FlowDefinitionStatus;
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

  @BeforeEach
  void clean() {
    flowJobRepository.deleteAll();
    flowEventRepository.deleteAll();
    flowStepExecutionRepository.deleteAll();
    flowSessionRepository.deleteAll();
    flowDefinitionHistoryRepository.deleteAll();
    flowDefinitionRepository.deleteAll();
  }

  @Test
  void createUpdatePublishAndFetchDefinitions() throws Exception {
    ObjectNode definitionBody = buildDefinitionBody();

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

  private ObjectNode buildDefinitionBody() {
    ObjectNode root = objectMapper.createObjectNode();
    root.put("title", "Onboarding flow");
    root.put("startStepId", "step-1");
    ArrayNode steps = root.putArray("steps");
    ObjectNode step = steps.addObject();
    step.put("id", "step-1");
    step.put("name", "Gather requirements");
    step.put("agentVersionId", UUID.randomUUID().toString());
    step.put("prompt", "Do something");
    step.putArray("memoryReads");
    step.putArray("memoryWrites");
    ObjectNode transitions = step.putObject("transitions");
    transitions.putObject("onSuccess").put("complete", true);
    transitions.putObject("onFailure").put("fail", true);
    return root;
  }
}
