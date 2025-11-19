package com.aiadvent.mcp.backend.coding;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
class CodingOpenAiConfiguration {

  @Bean(name = "codingArtifactChatClientBuilder")
  @ConditionalOnProperty(
      prefix = "coding.openai", name = "enabled", havingValue = "true", matchIfMissing = true)
  ChatClient.Builder codingArtifactChatClientBuilder(
      CodingAssistantProperties properties,
      ObjectProvider<OpenAiChatModel> chatModelProvider,
      ObjectProvider<OpenAiApi> openAiApiProvider,
      ObjectProvider<RestClient.Builder> restClientBuilderProvider,
      ObjectProvider<WebClient.Builder> webClientBuilderProvider,
      Environment environment) {
    CodingAssistantProperties.OpenAiProperties openai =
        properties.getOpenai() == null
            ? new CodingAssistantProperties.OpenAiProperties()
            : properties.getOpenai();
    OpenAiChatOptions options =
        OpenAiChatOptions.builder()
            .model(openai.getModel())
            .temperature(openai.getTemperature())
            .maxTokens(openai.getMaxTokens())
            .build();

    OpenAiChatModel chatModel = chatModelProvider.getIfAvailable();
    if (chatModel == null) {
      OpenAiApi openAiApi = openAiApiProvider.getIfAvailable();
      if (openAiApi == null) {
        openAiApi =
            buildOpenAiApi(environment, restClientBuilderProvider, webClientBuilderProvider);
      }
      chatModel = OpenAiChatModel.builder().openAiApi(openAiApi).defaultOptions(options).build();
    }
    ChatClient baseClient = ChatClient.builder(chatModel).defaultOptions(options).build();
    return baseClient.mutate();
  }

  private OpenAiApi buildOpenAiApi(
      Environment environment,
      ObjectProvider<RestClient.Builder> restClientBuilderProvider,
      ObjectProvider<WebClient.Builder> webClientBuilderProvider) {
    String apiKey = environment.getProperty("spring.ai.openai.api-key");
    if (!StringUtils.hasText(apiKey)) {
      throw new IllegalStateException(
          "spring.ai.openai.api-key must be configured to use coding.generate_artifact");
    }
    OpenAiApi.Builder builder = OpenAiApi.builder().apiKey(apiKey);
    String baseUrl = environment.getProperty("spring.ai.openai.base-url");
    if (StringUtils.hasText(baseUrl)) {
      builder.baseUrl(baseUrl);
    }
    RestClient.Builder restClientBuilder =
        restClientBuilderProvider.getIfAvailable(RestClient::builder);
    if (restClientBuilder != null) {
      builder.restClientBuilder(restClientBuilder);
    }
    WebClient.Builder webClientBuilder =
        webClientBuilderProvider.getIfAvailable(WebClient::builder);
    if (webClientBuilder != null) {
      builder.webClientBuilder(webClientBuilder);
    }
    return builder.build();
  }
}
