package com.aiadvent.backend.chat.config;

import com.aiadvent.backend.chat.api.StructuredSyncResponse;
import com.aiadvent.backend.chat.provider.ChatProviderAdapter;
import com.aiadvent.backend.chat.provider.ChatProviderRegistry;
import com.aiadvent.backend.chat.provider.ChatProviderService;
import com.aiadvent.backend.chat.provider.OpenAiChatProviderAdapter;
import com.aiadvent.backend.chat.provider.ZhiPuAiChatProviderAdapter;
import com.aiadvent.backend.chat.token.TokenUsageEstimator;
import com.aiadvent.backend.chat.token.TokenUsageMetrics;
import com.aiadvent.backend.chat.logging.ChatLoggingSupport;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.zhipuai.api.ZhiPuAiApi;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

@Configuration
@EnableConfigurationProperties({ChatProvidersProperties.class, ChatResearchProperties.class})
public class ChatProviderConfiguration {

  @Bean
  public ChatProviderRegistry chatProviderRegistry(ChatProvidersProperties properties) {
    return new ChatProviderRegistry(properties);
  }

  @Bean
  public List<ChatProviderAdapter> chatProviderAdapters(
      ChatProvidersProperties properties,
      ObjectProvider<OpenAiApi> openAiApiProvider,
      ObjectProvider<ZhiPuAiApi> zhiPuAiApiProvider,
      MessageChatMemoryAdvisor chatMemoryAdvisor,
      ChatLoggingSupport chatLoggingSupport,
      ObjectProvider<SyncMcpToolCallbackProvider> mcpToolCallbackProvider) {

    ToolCallbackProvider toolCallbackProvider = mcpToolCallbackProvider.getIfAvailable();
    Map<String, ChatProvidersProperties.Provider> providers = properties.getProviders();
    List<ChatProviderAdapter> adapters = new ArrayList<>(providers.size());

    providers.forEach(
        (providerId, providerConfig) -> {
          if (providerConfig.getType() == ChatProviderType.OPENAI) {
            OpenAiApi openAiApi = openAiApiProvider.getIfAvailable();
            if (openAiApi == null) {
              OpenAiApi.Builder builder = OpenAiApi.builder();
              if (StringUtils.hasText(providerConfig.getBaseUrl())) {
                builder.baseUrl(providerConfig.getBaseUrl());
              }
              if (StringUtils.hasText(providerConfig.getApiKey())) {
                builder.apiKey(providerConfig.getApiKey());
              }
              if (StringUtils.hasText(providerConfig.getCompletionsPath())) {
                builder.completionsPath(providerConfig.getCompletionsPath());
              }
              if (StringUtils.hasText(providerConfig.getEmbeddingsPath())) {
                builder.embeddingsPath(providerConfig.getEmbeddingsPath());
              }
              openAiApi = builder.build();
            }
            adapters.add(
                new OpenAiChatProviderAdapter(
                    providerId,
                    providerConfig,
                    openAiApi,
                    chatMemoryAdvisor,
                    toolCallbackProvider,
                    chatLoggingSupport));
          } else if (providerConfig.getType() == ChatProviderType.ZHIPUAI) {
            ZhiPuAiApi zhiPuAiApi = zhiPuAiApiProvider.getIfAvailable();
            if (zhiPuAiApi == null) {
              ZhiPuAiApi.Builder builder = ZhiPuAiApi.builder();
              if (StringUtils.hasText(providerConfig.getBaseUrl())) {
                builder.baseUrl(providerConfig.getBaseUrl());
              }
              if (StringUtils.hasText(providerConfig.getApiKey())) {
                builder.apiKey(providerConfig.getApiKey());
              }
              if (StringUtils.hasText(providerConfig.getCompletionsPath())) {
                builder.completionsPath(providerConfig.getCompletionsPath());
              }
              if (StringUtils.hasText(providerConfig.getEmbeddingsPath())) {
                builder.embeddingsPath(providerConfig.getEmbeddingsPath());
              }
              zhiPuAiApi = builder.build();
            }
            adapters.add(
                new ZhiPuAiChatProviderAdapter(
                    providerId,
                    providerConfig,
                    zhiPuAiApi,
                    chatMemoryAdvisor,
                    toolCallbackProvider,
                    chatLoggingSupport));
          } else {
            throw new IllegalStateException(
                "Unsupported provider type for '" + providerId + "': " + providerConfig.getType());
          }
        });

    Assert.state(!adapters.isEmpty(), "At least one chat provider must be defined");
    return adapters;
  }

  @Bean
  public ChatProviderService chatProviderService(
      ChatProviderRegistry registry,
      List<ChatProviderAdapter> adapters,
      TokenUsageEstimator tokenUsageEstimator,
      TokenUsageMetrics tokenUsageMetrics) {
    return new ChatProviderService(registry, adapters, tokenUsageEstimator, tokenUsageMetrics);
  }

  @Bean
  public BeanOutputConverter<StructuredSyncResponse> structuredSyncResponseOutputConverter() {
    return new BeanOutputConverter<>(StructuredSyncResponse.class);
  }
}
