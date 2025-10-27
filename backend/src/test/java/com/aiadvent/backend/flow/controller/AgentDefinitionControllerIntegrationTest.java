package com.aiadvent.backend.flow.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aiadvent.backend.chat.config.ChatProviderType;
import com.aiadvent.backend.flow.TestAgentInvocationOptionsFactory;
import com.aiadvent.backend.flow.api.AgentDefinitionResponse;
import com.aiadvent.backend.flow.api.AgentDefinitionSummaryResponse;
import com.aiadvent.backend.flow.api.AgentVersionResponse;
import com.aiadvent.backend.flow.domain.AgentDefinition;
import com.aiadvent.backend.flow.domain.AgentVersion;
import com.aiadvent.backend.flow.domain.AgentVersionStatus;
import com.aiadvent.backend.flow.persistence.AgentCapabilityRepository;
import com.aiadvent.backend.flow.persistence.AgentDefinitionRepository;
import com.aiadvent.backend.flow.persistence.AgentVersionRepository;
import com.aiadvent.backend.support.PostgresTestContainer;
import com.fasterxml.jackson.core.type.TypeReference;
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
class AgentDefinitionControllerIntegrationTest extends PostgresTestContainer {

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private AgentDefinitionRepository agentDefinitionRepository;
  @Autowired private AgentVersionRepository agentVersionRepository;
  @Autowired private AgentCapabilityRepository agentCapabilityRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  @BeforeEach
  void clean() {
    jdbcTemplate.execute("TRUNCATE TABLE agent_capability, agent_version, agent_definition RESTART IDENTITY CASCADE");
  }

  @Test
  void createAgentDefinitionAndManageVersions() throws Exception {
    ObjectNode createPayload =
        objectMapper
            .createObjectNode()
            .put("identifier", "solution-architect")
            .put("displayName", "Solution Architect")
            .put("description", "Designs complex solutions")
            .put("active", true)
            .put("createdBy", "alice");

    MvcResult createResult =
        mockMvc
            .perform(
                post("/api/agents/definitions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(createPayload.toString()))
            .andExpect(status().isCreated())
            .andReturn();

    AgentDefinitionResponse created =
        objectMapper.readValue(
            createResult.getResponse().getContentAsString(), AgentDefinitionResponse.class);

    assertThat(created.identifier()).isEqualTo("solution-architect");
    assertThat(created.createdBy()).isEqualTo("alice");
    assertThat(created.updatedBy()).isEqualTo("alice");
    assertThat(created.versions()).isEmpty();

    UUID definitionId = created.id();

    mockMvc
        .perform(
            put("/api/agents/definitions/" + definitionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper
                        .createObjectNode()
                        .put("identifier", "solution-architect")
                        .put("displayName", "Solution Architect v2")
                        .put("description", "Updated description")
                        .put("active", true)
                        .put("updatedBy", "bob")
                        .toString()))
        .andExpect(status().isOk());

    mockMvc
        .perform(
            patch("/api/agents/definitions/" + definitionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper
                        .createObjectNode()
                        .put("active", false)
                        .put("updatedBy", "carol")
                        .toString()))
        .andExpect(status().isOk());

    MvcResult listResult =
        mockMvc.perform(get("/api/agents/definitions")).andExpect(status().isOk()).andReturn();

    List<AgentDefinitionSummaryResponse> summaries =
        objectMapper.readValue(
            listResult.getResponse().getContentAsString(),
            new TypeReference<List<AgentDefinitionSummaryResponse>>() {});

    assertThat(summaries).hasSize(1);
    AgentDefinitionSummaryResponse summary = summaries.get(0);
    assertThat(summary.active()).isFalse();
    assertThat(summary.createdBy()).isEqualTo("alice");
    assertThat(summary.updatedBy()).isEqualTo("carol");
    assertThat(summary.latestVersion()).isNull();
    assertThat(summary.latestPublishedVersion()).isNull();

    ObjectNode versionPayload = objectMapper.createObjectNode();
    versionPayload.put("providerType", ChatProviderType.OPENAI.name());
    versionPayload.put("providerId", "openai");
    versionPayload.put("modelId", "gpt-4o-mini");
    versionPayload.put("systemPrompt", "You are a helpful solution architect.");
    versionPayload.set(
        "invocationOptions",
        TestAgentInvocationOptionsFactory.minimalJson(
            objectMapper, ChatProviderType.OPENAI, "openai", "gpt-4o-mini"));
    versionPayload.put("createdBy", "dave");
    ArrayNode capabilities = versionPayload.putArray("capabilities");
    capabilities
        .addObject()
        .put("capability", "planning")
        .set("payload", objectMapper.createObjectNode().put("priority", "high"));
    capabilities
        .addObject()
        .put("capability", "analysis")
        .set("payload", objectMapper.createObjectNode().put("tier", "expert"));

    MvcResult versionResult =
        mockMvc
            .perform(
                post("/api/agents/definitions/" + definitionId + "/versions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(versionPayload.toString()))
            .andExpect(status().isCreated())
            .andReturn();

    AgentVersionResponse draftVersion =
        objectMapper.readValue(
            versionResult.getResponse().getContentAsString(), AgentVersionResponse.class);

    assertThat(draftVersion.status()).isEqualTo(AgentVersionStatus.DRAFT);
    assertThat(draftVersion.version()).isEqualTo(1);

    UUID versionId = draftVersion.id();
    assertThat(draftVersion.createdBy()).isEqualTo("dave");
    assertThat(draftVersion.updatedBy()).isEqualTo("dave");
    assertThat(draftVersion.capabilities()).hasSize(2);

    MvcResult versionsList =
        mockMvc
            .perform(get("/api/agents/definitions/" + definitionId + "/versions"))
            .andExpect(status().isOk())
            .andReturn();

    List<AgentVersionResponse> versions =
        objectMapper.readValue(
            versionsList.getResponse().getContentAsString(),
            new TypeReference<List<AgentVersionResponse>>() {});

    assertThat(versions)
        .extracting(AgentVersionResponse::status)
        .containsExactly(AgentVersionStatus.DRAFT);

    ObjectNode publishPayload =
        objectMapper
            .createObjectNode()
            .put("updatedBy", "eve");
    publishPayload.set("capabilities", objectMapper.createArrayNode().add(
        objectMapper.createObjectNode()
            .put("capability", "planning")
            .set("payload", objectMapper.createObjectNode().put("priority", "critical"))));

    MvcResult publishResult =
        mockMvc
            .perform(
                post("/api/agents/versions/" + versionId + "/publish")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(publishPayload.toString()))
            .andExpect(status().isOk())
            .andReturn();

    AgentVersionResponse published =
        objectMapper.readValue(
            publishResult.getResponse().getContentAsString(), AgentVersionResponse.class);

    assertThat(published.status()).isEqualTo(AgentVersionStatus.PUBLISHED);
    assertThat(published.publishedAt()).isNotNull();
    assertThat(published.updatedBy()).isEqualTo("eve");
    assertThat(published.capabilities()).hasSize(1);
    assertThat(published.capabilities().get(0).capability()).isEqualTo("planning");

    MvcResult refreshedSummaryResult =
        mockMvc.perform(get("/api/agents/definitions")).andExpect(status().isOk()).andReturn();

    List<AgentDefinitionSummaryResponse> refreshedSummaries =
        objectMapper.readValue(
            refreshedSummaryResult.getResponse().getContentAsString(),
            new TypeReference<List<AgentDefinitionSummaryResponse>>() {});

    AgentDefinitionSummaryResponse refreshedSummary = refreshedSummaries.get(0);
    assertThat(refreshedSummary.active()).isTrue();
    assertThat(refreshedSummary.updatedBy()).isEqualTo("eve");
    assertThat(refreshedSummary.latestVersion()).isEqualTo(1);
    assertThat(refreshedSummary.latestPublishedVersion()).isEqualTo(1);
    assertThat(refreshedSummary.latestPublishedAt()).isNotNull();

    MvcResult deprecateResult =
        mockMvc
            .perform(post("/api/agents/versions/" + versionId + "/deprecate"))
            .andExpect(status().isOk())
            .andReturn();

    AgentVersionResponse deprecated =
        objectMapper.readValue(
            deprecateResult.getResponse().getContentAsString(), AgentVersionResponse.class);

    assertThat(deprecated.status()).isEqualTo(AgentVersionStatus.DEPRECATED);

    AgentDefinition persistedDefinition = agentDefinitionRepository.findById(definitionId).orElseThrow();
    assertThat(persistedDefinition.isActive()).isTrue();
    assertThat(agentCapabilityRepository.findByAgentVersionOrderByIdAsc(
            agentVersionRepository.findById(versionId).orElseThrow()))
        .hasSize(1);

    AgentVersion persistedVersion = agentVersionRepository.findById(versionId).orElseThrow();
    assertThat(persistedVersion.getStatus()).isEqualTo(AgentVersionStatus.DEPRECATED);
  }

  @Test
  void rejectInvalidVersionRequests() throws Exception {
    AgentDefinition definition =
        new AgentDefinition("analytics-engineer", "Analytics Engineer", null, true);
    definition.setCreatedBy("system");
    definition.setUpdatedBy("system");
    definition = agentDefinitionRepository.save(definition);

    mockMvc
        .perform(
            post("/api/agents/definitions/" + definition.getId() + "/versions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper
                        .createObjectNode()
                        .put("providerId", "unknown")
                        .put("modelId", "gpt-4o-mini")
                        .put("systemPrompt", "Test prompt")
                        .put("createdBy", "mallory")
                        .set(
                            "invocationOptions",
                            TestAgentInvocationOptionsFactory.minimalJson(
                                objectMapper, ChatProviderType.OPENAI, "unknown", "gpt-4o-mini"))
                        .toString()))
        .andExpect(status().isBadRequest());

    mockMvc
        .perform(
            post("/api/agents/definitions/" + definition.getId() + "/versions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper
                        .createObjectNode()
                        .put("providerType", ChatProviderType.ZHIPUAI.name())
                        .put("providerId", "openai")
                        .put("modelId", "gpt-4o-mini")
                        .put("systemPrompt", "Test prompt")
                        .put("createdBy", "mallory")
                        .set(
                            "invocationOptions",
                            TestAgentInvocationOptionsFactory.minimalJson(
                                objectMapper, ChatProviderType.OPENAI, "openai", "gpt-4o-mini"))
                        .toString()))
        .andExpect(status().isUnprocessableEntity());

    MvcResult invalidInvocationOptionsResult =
        mockMvc
            .perform(
                post("/api/agents/definitions/" + definition.getId() + "/versions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        objectMapper
                            .createObjectNode()
                            .put("providerType", ChatProviderType.OPENAI.name())
                            .put("providerId", "openai")
                            .put("modelId", "gpt-4o-mini")
                            .put("systemPrompt", "Test prompt")
                            .put("createdBy", "mallory")
                            .set("invocationOptions", objectMapper.createObjectNode())
                            .toString()))
            .andReturn();

    int status = invalidInvocationOptionsResult.getResponse().getStatus();
    String body = invalidInvocationOptionsResult.getResponse().getContentAsString();
    assertThat(status).withFailMessage("expected 400 but was %s. body=%s", status, body).isEqualTo(400);
  }
}
