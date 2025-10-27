package com.aiadvent.backend.flow.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aiadvent.backend.chat.config.ChatProviderType;
import com.aiadvent.backend.flow.api.AgentConstructorPreviewResponse;
import com.aiadvent.backend.flow.api.AgentConstructorProvidersResponse;
import com.aiadvent.backend.flow.api.AgentConstructorValidateResponse;
import com.aiadvent.backend.support.PostgresTestContainer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
class AgentConstructorControllerIntegrationTest extends PostgresTestContainer {

  @Autowired private MockMvc mockMvc;

  @Autowired private ObjectMapper objectMapper;

  @Test
  void providersEndpointReturnsConfiguredCatalog() throws Exception {
    MvcResult response =
        mockMvc.perform(get("/api/agents/constructor/providers"))
            .andExpect(status().isOk())
            .andReturn();

    AgentConstructorProvidersResponse payload =
        objectMapper.readValue(response.getResponse().getContentAsString(), AgentConstructorProvidersResponse.class);

    assertThat(payload.providers())
        .isNotEmpty()
        .anySatisfy(
            provider -> {
              assertThat(provider.id()).isEqualTo("openai");
              assertThat(provider.models()).isNotEmpty();
            });
  }

  @Test
  void validateEndpointAcceptsMinimalOptions() throws Exception {
    ObjectNode requestBody = objectMapper.createObjectNode();
    requestBody.set(
        "options",
        com.aiadvent.backend.flow.TestAgentInvocationOptionsFactory.minimalJson(
            objectMapper, ChatProviderType.OPENAI, "openai", "gpt-4o"));

    MvcResult response =
        mockMvc
            .perform(
                post("/api/agents/constructor/validate")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(requestBody.toString()))
            .andExpect(status().isOk())
            .andReturn();

    AgentConstructorValidateResponse payload =
        objectMapper.readValue(response.getResponse().getContentAsString(), AgentConstructorValidateResponse.class);

    assertThat(payload.issues()).isEmpty();
  }

  @Test
  void previewEndpointReturnsDiffCostAndCoverage() throws Exception {
    ObjectNode baseline =
        com.aiadvent.backend.flow.TestAgentInvocationOptionsFactory.minimalJson(
            objectMapper, ChatProviderType.OPENAI, "openai", "gpt-4o-mini");

    ObjectNode proposed =
        com.aiadvent.backend.flow.TestAgentInvocationOptionsFactory.minimalJson(
            objectMapper, ChatProviderType.OPENAI, "openai", "gpt-4o");
    proposed.with("prompt").with("generation").put("maxOutputTokens", 1_024);
    proposed
        .with("tooling")
        .withArray("bindings")
        .add(objectMapper.createObjectNode().put("toolCode", "perplexity_search"));

    ObjectNode requestBody = objectMapper.createObjectNode();
    requestBody.set("proposed", proposed);
    requestBody.set("baseline", baseline);
    requestBody.put("promptSample", "Preview prompt sample");

    MvcResult response =
        mockMvc
            .perform(
                post("/api/agents/constructor/preview")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(requestBody.toString()))
            .andExpect(status().isOk())
            .andReturn();

    AgentConstructorPreviewResponse payload =
        objectMapper.readValue(response.getResponse().getContentAsString(), AgentConstructorPreviewResponse.class);

    assertThat(payload.diff()).isNotNull();
    assertThat(payload.costEstimate().completionTokens()).isEqualTo(1_024L);
    assertThat(payload.toolCoverage())
        .extracting(
            AgentConstructorPreviewResponse.ToolCoverage::toolCode,
            AgentConstructorPreviewResponse.ToolCoverage::available)
        .containsExactlyInAnyOrder(tuple("perplexity_search", true));
  }
}
