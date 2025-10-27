package com.aiadvent.backend.flow.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aiadvent.backend.chat.config.ChatProviderType;
import com.aiadvent.backend.flow.TestAgentInvocationOptionsFactory;
import com.aiadvent.backend.flow.agent.options.AgentInvocationOptions;
import com.aiadvent.backend.flow.api.AgentCapabilityResponse;
import com.aiadvent.backend.flow.api.AgentDefinitionResponse;
import com.aiadvent.backend.flow.api.AgentDefinitionSummaryResponse;
import com.aiadvent.backend.flow.api.AgentVersionResponse;
import com.aiadvent.backend.flow.api.AgentTemplateResponse;
import com.aiadvent.backend.flow.domain.AgentDefinition;
import com.aiadvent.backend.flow.domain.AgentVersion;
import com.aiadvent.backend.flow.domain.AgentVersionStatus;
import com.aiadvent.backend.flow.persistence.AgentCapabilityRepository;
import com.aiadvent.backend.flow.persistence.AgentDefinitionRepository;
import com.aiadvent.backend.flow.persistence.AgentVersionRepository;
import com.aiadvent.backend.support.PostgresTestContainer;
import com.fasterxml.jackson.core.type.TypeReference;
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
  void listTemplatesIncludesPerplexityResearch() throws Exception {
    MvcResult result =
        mockMvc.perform(get("/api/agents/templates")).andExpect(status().isOk()).andReturn();

    AgentTemplateResponse response =
        objectMapper.readValue(
            result.getResponse().getContentAsString(), AgentTemplateResponse.class);

    assertThat(response.templates()).isNotEmpty();
    AgentTemplateResponse.AgentTemplate template =
        response.templates().stream()
            .filter(t -> t.identifier().equals("perplexity-research"))
            .findFirst()
            .orElseThrow();

    assertThat(template.displayName()).isEqualTo("Perplexity Research");
    assertThat(template.syncOnly()).isTrue();
    assertThat(template.maxTokens()).isEqualTo(1_400);

    AgentInvocationOptions options = template.invocationOptions();
    assertThat(options.provider().id()).isEqualTo("openai");
    assertThat(options.provider().modelId()).isEqualTo("gpt-4o-mini");
    assertThat(options.tooling().bindings())
        .extracting(AgentInvocationOptions.ToolBinding::toolCode)
        .containsExactly("perplexity_search", "perplexity_deep_research");

    AgentTemplateResponse.AgentTemplateCapability capability =
        template.capabilities().stream()
            .filter(cap -> cap.capability().equals("perplexity.tools"))
            .findFirst()
            .orElseThrow();
    assertThat(capability.payload().path("default").asText()).isEqualTo("perplexity_search");
    JsonNode optionsNode = capability.payload().path("options");
    assertThat(optionsNode.isArray()).isTrue();
    java.util.Set<String> optionCodes = new java.util.HashSet<>();
    optionsNode.forEach(node -> optionCodes.add(node.asText()));
    assertThat(optionCodes).contains("perplexity_search", "perplexity_deep_research");
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

    MvcResult fetchVersionResult =
        mockMvc
            .perform(get("/api/agents/versions/" + versionId))
            .andExpect(status().isOk())
            .andReturn();

    AgentVersionResponse fetchedDraft =
        objectMapper.readValue(
            fetchVersionResult.getResponse().getContentAsString(), AgentVersionResponse.class);

    assertThat(fetchedDraft.id()).isEqualTo(versionId);
    assertThat(fetchedDraft.systemPrompt()).isEqualTo("You are a helpful solution architect.");

    ObjectNode updatePayload = objectMapper.createObjectNode();
    updatePayload.put("systemPrompt", "You are a refined solution architect.");
    updatePayload.set(
        "invocationOptions",
        TestAgentInvocationOptionsFactory.minimalJson(
            objectMapper, ChatProviderType.OPENAI, "openai", "gpt-4o-mini"));
    updatePayload.put("syncOnly", false);
    updatePayload.put("maxTokens", 2048);
    updatePayload.put("updatedBy", "frank");
    ArrayNode updatedCapabilities = updatePayload.putArray("capabilities");
    updatedCapabilities
        .addObject()
        .put("capability", "analysis")
        .set("payload", objectMapper.createObjectNode().put("tier", "principal"));

    MvcResult updateVersionResult =
        mockMvc
            .perform(
                put("/api/agents/versions/" + versionId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(updatePayload.toString()))
            .andExpect(status().isOk())
            .andReturn();

    AgentVersionResponse updatedVersion =
        objectMapper.readValue(
            updateVersionResult.getResponse().getContentAsString(), AgentVersionResponse.class);

    assertThat(updatedVersion.systemPrompt()).isEqualTo("You are a refined solution architect.");
    assertThat(updatedVersion.syncOnly()).isFalse();
    assertThat(updatedVersion.maxTokens()).isEqualTo(2048);
    assertThat(updatedVersion.updatedBy()).isEqualTo("frank");
    assertThat(updatedVersion.capabilities())
        .extracting(AgentCapabilityResponse::capability)
        .containsExactly("analysis");

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

    mockMvc
        .perform(
            put("/api/agents/versions/" + versionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(updatePayload.toString()))
        .andExpect(status().isConflict());

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
