package com.aiadvent.backend.flow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.when;

import com.aiadvent.backend.chat.config.ChatProviderType;
import com.aiadvent.backend.chat.config.ChatProvidersProperties;
import com.aiadvent.backend.flow.agent.options.AgentInvocationOptions;
import com.aiadvent.backend.flow.api.AgentConstructorPoliciesResponse;
import com.aiadvent.backend.flow.api.AgentConstructorPreviewRequest;
import com.aiadvent.backend.flow.api.AgentConstructorPreviewResponse;
import com.aiadvent.backend.flow.api.AgentConstructorProvidersResponse;
import com.aiadvent.backend.flow.api.AgentConstructorToolsResponse;
import com.aiadvent.backend.flow.api.AgentConstructorValidateRequest;
import com.aiadvent.backend.flow.api.AgentConstructorValidateResponse;
import com.aiadvent.backend.flow.api.ValidationIssue;
import com.aiadvent.backend.flow.api.ValidationIssue.IssueSeverity;
import com.aiadvent.backend.flow.tool.domain.ToolDefinition;
import com.aiadvent.backend.flow.tool.domain.ToolDefinition.ToolCallType;
import com.aiadvent.backend.flow.tool.domain.ToolSchemaVersion;
import com.aiadvent.backend.flow.tool.persistence.ToolDefinitionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AgentConstructorServiceTest {

  @Mock private ToolDefinitionRepository toolDefinitionRepository;

  private AgentConstructorService service;
  private ObjectMapper objectMapper;

  @BeforeEach
  void setUp() {
    objectMapper = new ObjectMapper();
    ChatProvidersProperties properties = new ChatProvidersProperties();
    properties.setDefaultProvider("openai");

    ChatProvidersProperties.Provider provider = new ChatProvidersProperties.Provider();
    provider.setType(ChatProviderType.OPENAI);
    provider.setDisplayName("OpenAI");
    provider.setBaseUrl("https://api.openai.com");
    provider.setDefaultModel("gpt-4o");

    ChatProvidersProperties.Model model = new ChatProvidersProperties.Model();
    model.setDisplayName("GPT-4o");
    model.setStreamingEnabled(true);
    model.setStructuredEnabled(true);
    model.setContextWindow(128_000);
    model.setMaxOutputTokens(4_096);
    model.getPricing().setInputPer1KTokens(BigDecimal.valueOf(0.01));
    model.getPricing().setOutputPer1KTokens(BigDecimal.valueOf(0.03));
    model.getPricing().setCurrency("USD");

    provider.getModels().put("gpt-4o", model);
    properties.getProviders().put("openai", provider);

    ChatProvidersProperties.Model altModel = new ChatProvidersProperties.Model();
    altModel.setDisplayName("GPT-4o Mini");
    altModel.setStreamingEnabled(true);
    altModel.setStructuredEnabled(true);
    provider.getModels().put("gpt-4o-mini", altModel);

    service =
        new AgentConstructorService(
            new com.aiadvent.backend.chat.provider.ChatProviderRegistry(properties),
            toolDefinitionRepository,
            objectMapper);
  }

  @Test
  void listProvidersReturnsMappedModels() {
    AgentConstructorProvidersResponse response = service.listProviders();

    assertThat(response.providers())
        .hasSize(1)
        .extracting(
            AgentConstructorProvidersResponse.Provider::id,
            AgentConstructorProvidersResponse.Provider::displayName)
        .containsExactly(tuple("openai", "OpenAI"));

    AgentConstructorProvidersResponse.Provider provider = response.providers().get(0);
    assertThat(provider.models())
        .extracting(
            AgentConstructorProvidersResponse.Model::id,
            AgentConstructorProvidersResponse.Model::structuredEnabled)
        .containsExactly(
            tuple("gpt-4o", true), tuple("gpt-4o-mini", true));
  }

  @Test
  void listToolsMapsCatalogEntries() {
    ToolSchemaVersion schemaVersion =
        new ToolSchemaVersion(
            "perplexity-research",
            1,
            objectMapper.createObjectNode(),
            objectMapper.createObjectNode(),
            "checksum",
            objectMapper.createArrayNode(),
            "perplexity",
            "perplexity_research",
            "stdio",
            "perplexity");

    ToolDefinition toolDefinition =
        new ToolDefinition(
            "perplexity-research",
            "Perplexity Research",
            "Research tool",
            "perplexity",
            ToolCallType.AUTO,
            List.of("research"),
            List.of("web-search"),
            "Usage billed separately",
            null,
            60_000L);
    toolDefinition.setSchemaVersion(schemaVersion);

    when(toolDefinitionRepository.findAllByOrderByDisplayNameAsc())
        .thenReturn(List.of(toolDefinition));

    AgentConstructorToolsResponse response = service.listTools();

    assertThat(response.tools())
        .hasSize(1)
        .extracting(AgentConstructorToolsResponse.Tool::code, AgentConstructorToolsResponse.Tool::callType)
        .containsExactly(tuple("perplexity-research", ToolCallType.AUTO));
    assertThat(response.tools().get(0).schemaVersion().version()).isEqualTo(1);
  }

  @Test
  void validateReturnsIssuesForUnknownProviderAndPromptDefaults() throws Exception {
    AgentInvocationOptions options = minimalOptionsWithProvider("unknown");

    AgentConstructorValidateResponse response =
        service.validate(new AgentConstructorValidateRequest(options));

    assertThat(response.issues())
        .extracting(ValidationIssue::path, ValidationIssue::severity)
        .contains(tuple("/provider/id", IssueSeverity.ERROR));
  }

  @Test
  void previewProducesDiffCostEstimateAndCoverage() throws Exception {
    ToolDefinition toolDefinition =
        new ToolDefinition(
            "perplexity-research",
            "Perplexity Research",
            "Research tool",
            "perplexity",
            ToolCallType.AUTO,
            List.of("research"),
            List.of("web-search"),
            "Usage billed separately",
            null,
            60_000L);
    when(toolDefinitionRepository.findAll()).thenReturn(List.of(toolDefinition));

    AgentInvocationOptions baseline = minimalOptionsWithProvider("openai");

    ObjectNode proposedJson =
        com.aiadvent.backend.flow.TestAgentInvocationOptionsFactory.minimalJson(
            objectMapper, ChatProviderType.OPENAI, "openai", "gpt-4o");
    proposedJson.with("prompt").with("generation").put("maxOutputTokens", 1_024);
    proposedJson
        .with("tooling")
        .withArray("bindings")
        .add(objectMapper.createObjectNode().put("toolCode", "perplexity-research"));
    AgentInvocationOptions proposed =
        objectMapper.treeToValue(proposedJson, AgentInvocationOptions.class);

    AgentConstructorPreviewResponse response =
        service.preview(
            new AgentConstructorPreviewRequest(proposed, baseline, "Sample prompt for preview"));

    assertThat(response.diff()).isNotNull();
    assertThat(response.costEstimate().currency()).isEqualTo("USD");
    assertThat(response.costEstimate().completionTokens()).isEqualTo(1_024L);
    assertThat(response.toolCoverage())
        .extracting(
            AgentConstructorPreviewResponse.ToolCoverage::toolCode,
            AgentConstructorPreviewResponse.ToolCoverage::available)
        .containsExactly(tuple("perplexity-research", true));
  }

  @Test
  void listPoliciesProvidesPresetPayloads() {
    AgentConstructorPoliciesResponse response = service.listPolicies();

    assertThat(response.retryPolicies()).isNotEmpty();
    assertThat(response.memoryPolicies()).isNotEmpty();
    assertThat(response.advisorPolicies()).isNotEmpty();
  }

  private AgentInvocationOptions minimalOptionsWithProvider(String providerId) throws Exception {
    ObjectNode json =
        com.aiadvent.backend.flow.TestAgentInvocationOptionsFactory.minimalJson(
            objectMapper, ChatProviderType.OPENAI, providerId, "gpt-4o");
    return objectMapper.treeToValue(json, AgentInvocationOptions.class);
  }
}
