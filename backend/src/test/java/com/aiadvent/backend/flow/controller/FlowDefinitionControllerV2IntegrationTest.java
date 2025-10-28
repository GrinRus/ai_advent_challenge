package com.aiadvent.backend.flow.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aiadvent.backend.chat.config.ChatProviderType;
import com.aiadvent.backend.flow.TestAgentInvocationOptionsFactory;
import com.aiadvent.backend.flow.api.FlowDefinitionResponseV2;
import com.aiadvent.backend.flow.api.FlowLaunchPreviewResponseV2;
import com.aiadvent.backend.flow.api.FlowMemoryReferenceResponse;
import com.aiadvent.backend.flow.api.FlowStepValidationResponse;
import com.aiadvent.backend.flow.api.FlowValidationIssue;
import com.aiadvent.backend.flow.blueprint.FlowBlueprintMemoryChannel;
import com.aiadvent.backend.flow.blueprint.FlowBlueprintSchemaVersion;
import com.aiadvent.backend.flow.domain.AgentDefinition;
import com.aiadvent.backend.flow.domain.AgentVersion;
import com.aiadvent.backend.flow.domain.AgentVersionStatus;
import com.aiadvent.backend.flow.persistence.AgentDefinitionRepository;
import com.aiadvent.backend.flow.persistence.AgentVersionRepository;
import com.aiadvent.backend.support.PostgresTestContainer;
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

@SpringBootTest(properties = "app.flow.api.v2-enabled=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FlowDefinitionControllerV2IntegrationTest extends PostgresTestContainer {

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private AgentDefinitionRepository agentDefinitionRepository;
  @Autowired private AgentVersionRepository agentVersionRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  @BeforeEach
  void clean() {
    jdbcTemplate.execute(
        "TRUNCATE TABLE flow_event, flow_step_execution, flow_job, flow_session, flow_definition_history, flow_definition, agent_capability, agent_version, agent_definition RESTART IDENTITY CASCADE");
  }

  @Test
  void definitionEndpointsReturnBlueprintWhenV2Enabled() throws Exception {
    UUID agentVersionId = createAgentVersion();
    ObjectNode definitionBody = buildDefinitionBody(agentVersionId);

    // Create definition
    MvcResult createResult =
        mockMvc
            .perform(
                post("/api/flows/definitions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        objectMapper
                            .createObjectNode()
                            .put("name", "v2-demo-flow")
                            .put("description", "Demo flow for v2 API")
                            .put("updatedBy", "v2-user")
                            .set("definition", definitionBody)
                            .toString()))
            .andExpect(status().isCreated())
            .andReturn();

    FlowDefinitionResponseV2 created =
        objectMapper.readValue(createResult.getResponse().getContentAsString(), FlowDefinitionResponseV2.class);

    assertThat(created.definition()).isNotNull();
    assertThat(created.definition().steps()).hasSize(1);
    assertThat(created.definition().memory().sharedChannels())
        .anySatisfy(
            channel -> {
              assertThat(channel.id()).isEqualTo("analytics");
              assertThat(channel.retentionVersions()).isEqualTo(3);
              assertThat(channel.retentionDays()).isEqualTo(2);
            });
    UUID definitionId = created.id();

    // Update definition to ensure PUT also returns blueprint
    definitionBody.put("title", "Updated Title");
    MvcResult updateResult =
        mockMvc
            .perform(
                put("/api/flows/definitions/" + definitionId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        objectMapper
                            .createObjectNode()
                            .put("description", "Updated description")
                            .put("updatedBy", "v2-user")
                            .set("definition", definitionBody)
                            .toString()))
            .andExpect(status().isOk())
            .andReturn();

    FlowDefinitionResponseV2 updated =
        objectMapper.readValue(updateResult.getResponse().getContentAsString(), FlowDefinitionResponseV2.class);
    assertThat(updated.definition().metadata().title()).isEqualTo("Updated Title");
    assertThat(updated.definition().memory().sharedChannels())
        .extracting(FlowBlueprintMemoryChannel::id)
        .contains("analytics");

    // Publish to access preview
    mockMvc
        .perform(
            post("/api/flows/definitions/" + definitionId + "/publish")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper
                        .createObjectNode()
                        .put("updatedBy", "approver")
                        .toString()))
        .andExpect(status().isOk());

    // Fetch definition and ensure blueprint present
    MvcResult getResult =
        mockMvc.perform(get("/api/flows/definitions/" + definitionId))
            .andExpect(status().isOk())
            .andReturn();

    FlowDefinitionResponseV2 fetched =
        objectMapper.readValue(getResult.getResponse().getContentAsString(), FlowDefinitionResponseV2.class);
    assertThat(fetched.definition().startStepId()).isEqualTo("step-1");
    assertThat(fetched.definition().schemaVersion())
        .isEqualTo(FlowBlueprintSchemaVersion.CURRENT);
    assertThat(fetched.definition().memory().sharedChannels())
        .extracting(FlowBlueprintMemoryChannel::retentionVersions)
        .contains(3);

    // Launch preview should return blueprint in payload
    MvcResult previewResult =
        mockMvc.perform(get("/api/flows/definitions/" + definitionId + "/launch-preview"))
            .andExpect(status().isOk())
            .andReturn();

    FlowLaunchPreviewResponseV2 preview =
        objectMapper.readValue(previewResult.getResponse().getContentAsString(), FlowLaunchPreviewResponseV2.class);
    assertThat(preview.blueprint()).isNotNull();
    assertThat(preview.blueprint().steps()).hasSize(1);
    assertThat(preview.blueprint().schemaVersion())
        .isEqualTo(FlowBlueprintSchemaVersion.CURRENT);
    assertThat(preview.blueprint().memory().sharedChannels())
        .extracting(FlowBlueprintMemoryChannel::id)
        .contains("analytics");
  }

  @Test
  void memoryReferenceEndpointReturnsCanonicalChannels() throws Exception {
    MvcResult response =
        mockMvc.perform(get("/api/flows/definitions/reference/memory-channels"))
            .andExpect(status().isOk())
            .andReturn();

    FlowMemoryReferenceResponse reference =
        objectMapper.readValue(response.getResponse().getContentAsString(), FlowMemoryReferenceResponse.class);

    assertThat(reference.channels()).extracting(FlowMemoryReferenceResponse.MemoryChannel::id)
        .containsExactly("conversation", "shared");
  }

  @Test
  void stepValidationEndpointDetectsMissingAgentVersion() throws Exception {
    ObjectNode blueprint = buildDefinitionBody(UUID.randomUUID());
    ((ObjectNode) blueprint.withArray("steps").get(0)).put("agentVersionId", UUID.randomUUID().toString());

    ObjectNode requestBody = objectMapper.createObjectNode();
    requestBody.set("blueprint", blueprint);
    requestBody.put("stepId", "step-1");

    MvcResult response =
        mockMvc
            .perform(
                post("/api/flows/definitions/validation/step")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(requestBody.toString()))
            .andExpect(status().isOk())
            .andReturn();

    FlowStepValidationResponse validation =
        objectMapper.readValue(response.getResponse().getContentAsString(), FlowStepValidationResponse.class);

    assertThat(validation.valid()).isFalse();
    assertThat(validation.errors())
        .extracting(FlowValidationIssue::code)
        .contains("AGENT_VERSION_NOT_FOUND");
  }

  private UUID createAgentVersion() {
    AgentDefinition agentDefinition =
        agentDefinitionRepository.save(
            new AgentDefinition("v2-agent", "V2 Agent", "Agent for v2 testing", true));
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
    root.put("title", "V2 Demo Flow");
    root.put("startStepId", "step-1");
    ObjectNode memory = root.putObject("memory");
    ArrayNode channels = memory.putArray("sharedChannels");
    ObjectNode analytics = channels.addObject();
    analytics.put("id", "analytics");
    analytics.put("retentionVersions", 3);
    analytics.put("retentionDays", 2);
    ArrayNode steps = root.putArray("steps");
    ObjectNode step = steps.addObject();
    step.put("id", "step-1");
    step.put("name", "Gather data");
    step.put("agentVersionId", agentVersionId.toString());
    step.put("prompt", "Collect customer info");
    step.putArray("memoryReads");
    step.putArray("memoryWrites");
    ObjectNode transitions = step.putObject("transitions");
    transitions.putObject("onSuccess").put("complete", true);
    transitions.putObject("onFailure").put("fail", true);
    return root;
  }
}
