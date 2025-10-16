package com.aiadvent.backend.chat.provider;

import com.aiadvent.backend.chat.config.ChatProviderType;
import com.aiadvent.backend.chat.config.ChatProvidersProperties;
import com.aiadvent.backend.chat.provider.model.ChatProviderSelection;
import com.aiadvent.backend.chat.provider.model.ChatRequestOverrides;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.zhipuai.ZhiPuAiChatModel;
import org.springframework.ai.zhipuai.ZhiPuAiChatOptions;
import org.springframework.ai.zhipuai.api.ZhiPuAiApi;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

public class ZhiPuAiChatProviderAdapter implements ChatProviderAdapter {

  private final String providerId;
  private final ChatProvidersProperties.Provider providerConfig;
  private final ChatClient chatClient;

  public ZhiPuAiChatProviderAdapter(
      String providerId,
      ChatProvidersProperties.Provider providerConfig,
      ZhiPuAiApi baseZhiPuAiApi,
      SimpleLoggerAdvisor simpleLoggerAdvisor,
      MessageChatMemoryAdvisor chatMemoryAdvisor) {
    Assert.notNull(providerConfig, "providerConfig must not be null");
    Assert.state(
        providerConfig.getType() == ChatProviderType.ZHIPUAI,
        () -> "Invalid provider type for ZhiPu adapter: " + providerConfig.getType());

    this.providerId = providerId;
    this.providerConfig = providerConfig;

    ZhiPuAiApi providerApi = mutateApi(baseZhiPuAiApi, providerConfig);
    ZhiPuAiChatOptions defaultOptions = defaultOptions(providerConfig);
    ZhiPuAiChatModel chatModel = new ZhiPuAiChatModel(providerApi, defaultOptions);
    this.chatClient =
        ChatClient.builder(chatModel).defaultAdvisors(simpleLoggerAdvisor, chatMemoryAdvisor).build();
  }

  private ZhiPuAiApi mutateApi(ZhiPuAiApi baseZhiPuAiApi, ChatProvidersProperties.Provider provider) {
    ZhiPuAiApi.Builder builder = baseZhiPuAiApi.mutate();
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

  private ZhiPuAiChatOptions defaultOptions(ChatProvidersProperties.Provider provider) {
    ZhiPuAiChatOptions.Builder builder = ZhiPuAiChatOptions.builder();
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
    ZhiPuAiChatOptions.Builder builder = ZhiPuAiChatOptions.builder().model(selection.modelId());

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
