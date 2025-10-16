package com.aiadvent.backend.chat.provider;

import com.aiadvent.backend.chat.config.ChatProviderType;
import com.aiadvent.backend.chat.config.ChatProvidersProperties;
import com.aiadvent.backend.chat.provider.model.ChatProviderSelection;
import com.aiadvent.backend.chat.provider.model.ChatRequestOverrides;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

public class OpenAiChatProviderAdapter implements ChatProviderAdapter {

  private final String providerId;
  private final ChatProvidersProperties.Provider providerConfig;
  private final ChatClient chatClient;

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
    if (provider.getTemperature() != null) {
      builder.temperature(provider.getTemperature());
    }
    if (provider.getTopP() != null) {
      builder.topP(provider.getTopP());
    }
    if (provider.getMaxTokens() != null) {
      builder.maxTokens(provider.getMaxTokens());
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
  public ChatOptions buildOptions(ChatProviderSelection selection, ChatRequestOverrides overrides) {
    ChatRequestOverrides effectiveOverrides = overrides != null ? overrides : ChatRequestOverrides.empty();
    OpenAiChatOptions.Builder builder = OpenAiChatOptions.builder();
    builder.model(selection.modelId());

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
      builder.maxTokens(maxTokens);
    }

    return builder.build();
  }
}
