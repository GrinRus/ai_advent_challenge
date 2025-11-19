package com.aiadvent.mcp.backend.coding;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class CodingOpenAiConfiguration {

    private static final Logger log = LoggerFactory.getLogger(CodingOpenAiConfiguration.class);

    @Bean(name = "codingArtifactChatClientBuilder")
    @ConditionalOnProperty(prefix = "coding.openai", name = "enabled", havingValue = "true", matchIfMissing = true)
    ChatClient.Builder codingArtifactChatClientBuilder(CodingAssistantProperties properties, OpenAiChatModel chatModel) {
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
        ChatClient baseClient =
                ChatClient.builder(chatModel)
                        .defaultOptions(options)
                        .defaultAdvisors(SimpleLoggerAdvisor.builder().order(-100).build())
                        .build();
        return baseClient.mutate();
    }
}
