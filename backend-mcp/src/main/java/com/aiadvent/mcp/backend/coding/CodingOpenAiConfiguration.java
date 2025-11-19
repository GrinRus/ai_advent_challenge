package com.aiadvent.mcp.backend.coding;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class CodingOpenAiConfiguration {

  @Bean(name = "codingArtifactChatClientBuilder")
  @ConditionalOnBean(OpenAiChatModel.class)
  @ConditionalOnProperty(prefix = "coding.openai", name = "enabled", havingValue = "true", matchIfMissing = true)
  ChatClient.Builder codingArtifactChatClientBuilder(
      OpenAiChatModel chatModel, CodingAssistantProperties properties) {
    CodingAssistantProperties.OpenAiProperties openai =
        properties.getOpenai() == null ? new CodingAssistantProperties.OpenAiProperties() : properties.getOpenai();
    OpenAiChatOptions options =
        OpenAiChatOptions.builder()
            .model(openai.getModel())
            .temperature(openai.getTemperature())
            .maxTokens(openai.getMaxTokens())
            .build();
    return ChatClient.builder(chatModel).defaultOptions(options);
  }
}
