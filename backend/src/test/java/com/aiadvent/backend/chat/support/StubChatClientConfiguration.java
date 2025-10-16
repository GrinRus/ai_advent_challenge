package com.aiadvent.backend.chat.support;

import com.aiadvent.backend.chat.config.ChatProviderType;
import com.aiadvent.backend.chat.config.ChatProvidersProperties;
import com.aiadvent.backend.chat.provider.ChatProviderAdapter;
import com.aiadvent.backend.chat.provider.ChatProviderRegistry;
import com.aiadvent.backend.chat.provider.ChatProviderService;
import com.aiadvent.backend.chat.provider.model.ChatProviderSelection;
import com.aiadvent.backend.chat.provider.model.ChatRequestOverrides;
import jakarta.annotation.PostConstruct;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import reactor.core.publisher.Flux;

@TestConfiguration
public class StubChatClientConfiguration {

  private final JdbcTemplate jdbcTemplate;

  public StubChatClientConfiguration(DataSource dataSource) {
    this.jdbcTemplate = new JdbcTemplate(dataSource);
  }

  @PostConstruct
  void createMemoryTableIfMissing() {
    jdbcTemplate.execute(
        """
        CREATE TABLE IF NOT EXISTS chat_memory_message (
          id UUID PRIMARY KEY,
          session_id UUID NOT NULL,
          message_order INT NOT NULL,
          role VARCHAR(32) NOT NULL,
          content TEXT NOT NULL,
          metadata TEXT,
          created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
        )
        """);
    jdbcTemplate.execute(
        "CREATE INDEX IF NOT EXISTS idx_chat_memory_message_session_order ON chat_memory_message (session_id, message_order)");
  }

  @Bean
  @Primary
  public ChatProviderService stubChatProviderService(
      SimpleLoggerAdvisor simpleLoggerAdvisor, MessageChatMemoryAdvisor chatMemoryAdvisor) {
    ChatProvidersProperties properties = new ChatProvidersProperties();
    properties.setDefaultProvider("stub");

    ChatProvidersProperties.Provider provider = new ChatProvidersProperties.Provider();
    provider.setType(ChatProviderType.OPENAI);
    provider.setDefaultModel("stub-model");
    provider.setTemperature(0.7);
    provider.setTopP(1.0);
    provider.setMaxTokens(1024);
    ChatProvidersProperties.Model model = new ChatProvidersProperties.Model();
    model.setDisplayName("Stub Model");
    provider.getModels().put("stub-model", model);
    properties.getProviders().put("stub", provider);

    ChatProviderRegistry registry = new ChatProviderRegistry(properties);

    ChatClient chatClient =
        ChatClient.builder(new StubChatModel())
            .defaultAdvisors(simpleLoggerAdvisor, chatMemoryAdvisor)
            .build();

    ChatProviderAdapter adapter =
        new ChatProviderAdapter() {
          @Override
          public String providerId() {
            return "stub";
          }

          @Override
          public ChatClient chatClient() {
            return chatClient;
          }

          @Override
          public ChatOptions buildOptions(
              ChatProviderSelection selection, ChatRequestOverrides overrides) {
            return OpenAiChatOptions.builder().model(selection.modelId()).build();
          }
        };

    return new ChatProviderService(registry, List.of(adapter));
  }

  static class StubChatModel implements ChatModel {

    @Override
    public ChatResponse call(Prompt prompt) {
      StubChatClientState.capturePrompt(prompt);
      String aggregated = String.join("", StubChatClientState.currentTokens());
      Generation generation = new Generation(AssistantMessage.builder().content(aggregated).build());
      return new ChatResponse(java.util.List.of(generation));
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
      StubChatClientState.capturePrompt(prompt);
      return StubChatClientState.responseFlux();
    }
  }
}
