package com.aiadvent.backend.chat.provider;

import com.aiadvent.backend.chat.config.ChatProviderType;
import com.aiadvent.backend.chat.config.ChatProvidersProperties;
import com.aiadvent.backend.chat.provider.model.ChatProviderSelection;
import com.aiadvent.backend.chat.provider.model.ChatRequestOverrides;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.HashSet;
import java.util.Set;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.openai.api.ResponseFormat;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

public class OpenAiChatProviderAdapter implements ChatProviderAdapter {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private final String providerId;
  private final ChatProvidersProperties.Provider providerConfig;
  private final ChatClient chatClient;
  private final ChatClient statelessChatClient;

  public OpenAiChatProviderAdapter(
      String providerId,
      ChatProvidersProperties.Provider providerConfig,
      OpenAiApi baseOpenAiApi,
      SimpleLoggerAdvisor simpleLoggerAdvisor,
      MessageChatMemoryAdvisor chatMemoryAdvisor) {
    Assert.notNull(providerConfig, "providerConfig must not be null");
    Assert.state(
        providerConfig.getType() == ChatProviderType.OPENAI,
        () -> "Invalid provider type for OpenAI adapter: " + providerConfig.getType());

    this.providerId = providerId;
    this.providerConfig = providerConfig;
    OpenAiApi providerApi = mutateApi(baseOpenAiApi, providerConfig);
    OpenAiChatOptions defaultOptions = defaultOptions(providerConfig);
    OpenAiChatModel chatModel =
        OpenAiChatModel.builder().openAiApi(providerApi).defaultOptions(defaultOptions).build();
    this.chatClient =
        ChatClient.builder(chatModel).defaultAdvisors(simpleLoggerAdvisor, chatMemoryAdvisor).build();
    this.statelessChatClient = ChatClient.builder(chatModel).defaultAdvisors(simpleLoggerAdvisor).build();
  }

  private OpenAiApi mutateApi(OpenAiApi baseOpenAiApi, ChatProvidersProperties.Provider provider) {
    OpenAiApi.Builder builder = baseOpenAiApi.mutate();
    if (StringUtils.hasText(provider.getBaseUrl())) {
      builder.baseUrl(provider.getBaseUrl());
    }
    if (StringUtils.hasText(provider.getApiKey())) {
      builder.apiKey(provider.getApiKey());
    }
    if (StringUtils.hasText(provider.getCompletionsPath())) {
      builder.completionsPath(provider.getCompletionsPath());
    }
    if (StringUtils.hasText(provider.getEmbeddingsPath())) {
      builder.embeddingsPath(provider.getEmbeddingsPath());
    }
    return builder.build();
  }

  private OpenAiChatOptions defaultOptions(ChatProvidersProperties.Provider provider) {
    OpenAiChatOptions.Builder builder = OpenAiChatOptions.builder();
    if (StringUtils.hasText(provider.getDefaultModel())) {
      builder.model(provider.getDefaultModel());
    }
    return builder.build();
  }

  @Override
  public String providerId() {
    return providerId;
  }

  @Override
  public ChatClient chatClient() {
    return chatClient;
  }

  @Override
  public ChatClient statelessChatClient() {
    return statelessChatClient;
  }

  @Override
  public ChatOptions buildOptions(ChatProviderSelection selection, ChatRequestOverrides overrides) {
    return configureBuilder(selection, overrides).build();
  }

  @Override
  public ChatOptions buildStreamingOptions(
      ChatProviderSelection selection, ChatRequestOverrides overrides) {
    OpenAiChatOptions.Builder builder = configureBuilder(selection, overrides);
    builder.streamUsage(true);
    return builder.build();
  }

  @Override
  public ChatOptions buildStructuredOptions(
      ChatProviderSelection selection,
      ChatRequestOverrides overrides,
      BeanOutputConverter<?> outputConverter) {
    OpenAiChatOptions.Builder builder = configureBuilder(selection, overrides);
    ResponseFormat responseFormat =
        ResponseFormat.builder()
            .type(ResponseFormat.Type.JSON_SCHEMA)
            .jsonSchema(
                ResponseFormat.JsonSchema.builder()
                    .schema(enforceRequiredProperties(outputConverter))
                    .strict(Boolean.TRUE)
                    .build())
            .build();
    builder.responseFormat(responseFormat);
    return builder.build();
  }

  private OpenAiChatOptions.Builder configureBuilder(
      ChatProviderSelection selection, ChatRequestOverrides overrides) {
    ChatRequestOverrides effectiveOverrides = overrides != null ? overrides : ChatRequestOverrides.empty();
    OpenAiChatOptions.Builder builder = OpenAiChatOptions.builder();
    builder.model(selection.modelId());
    ChatProvidersProperties.Model modelConfig = providerConfig.getModels().get(selection.modelId());
    boolean useCompletionTokens = modelConfig != null && modelConfig.isUseCompletionTokens();

    Double temperature =
        effectiveOverrides.temperature() != null
            ? effectiveOverrides.temperature()
            : providerConfig.getTemperature();
    if (temperature != null) {
      builder.temperature(temperature);
    }

    Double topP =
        effectiveOverrides.topP() != null ? effectiveOverrides.topP() : providerConfig.getTopP();
    if (topP != null) {
      builder.topP(topP);
    }

    Integer maxTokens =
        effectiveOverrides.maxTokens() != null
            ? effectiveOverrides.maxTokens()
            : providerConfig.getMaxTokens();
    if (maxTokens != null) {
      if (useCompletionTokens) {
        builder.maxCompletionTokens(maxTokens);
      } else {
        builder.maxTokens(maxTokens);
      }
    }

    return builder;
  }

  private String enforceRequiredProperties(BeanOutputConverter<?> outputConverter) {
    String jsonSchema = outputConverter.getJsonSchema();
    try {
      JsonNode root = OBJECT_MAPPER.readTree(jsonSchema);
      ensureRequiredRecursively(root);
      return OBJECT_MAPPER.writeValueAsString(root);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Failed to prepare strict JSON schema for OpenAI", exception);
    }
  }

  private void ensureRequiredRecursively(JsonNode node) {
    if (node == null) {
      return;
    }

    if (node.isObject()) {
      ObjectNode objectNode = (ObjectNode) node;
      if (objectNode.has("properties") && objectNode.get("properties").isObject()) {
        ObjectNode propertiesNode = (ObjectNode) objectNode.get("properties");

        ArrayNode requiredNode;
        if (objectNode.has("required") && objectNode.get("required").isArray()) {
          requiredNode = (ArrayNode) objectNode.get("required");
        } else {
          requiredNode = objectNode.putArray("required");
        }

        Set<String> requiredNames = new HashSet<>();
        requiredNode.elements().forEachRemaining(element -> requiredNames.add(element.asText()));

        propertiesNode
            .fieldNames()
            .forEachRemaining(
                fieldName -> {
                  if (!requiredNames.contains(fieldName)) {
                    requiredNode.add(fieldName);
                    requiredNames.add(fieldName);
                  }
                  ensureRequiredRecursively(propertiesNode.get(fieldName));
                });
      }

      if (objectNode.has("items")) {
        ensureRequiredRecursively(objectNode.get("items"));
      }
    } else if (node.isArray()) {
      node.forEach(this::ensureRequiredRecursively);
    }
  }
}
