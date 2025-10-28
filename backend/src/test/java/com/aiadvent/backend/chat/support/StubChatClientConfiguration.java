package com.aiadvent.backend.chat.support;

import com.aiadvent.backend.chat.config.ChatProviderType;
import com.aiadvent.backend.chat.config.ChatProvidersProperties;
import com.aiadvent.backend.chat.logging.ChatLoggingSupport;
import com.aiadvent.backend.chat.provider.ChatProviderAdapter;
import com.aiadvent.backend.chat.provider.ChatProviderRegistry;
import com.aiadvent.backend.chat.provider.ChatProviderService;
import com.aiadvent.backend.chat.provider.model.ChatProviderSelection;
import com.aiadvent.backend.chat.provider.model.ChatRequestOverrides;
import com.aiadvent.backend.chat.token.TokenUsageEstimator;
import com.aiadvent.backend.chat.token.TokenUsageMetrics;
import jakarta.annotation.PostConstruct;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.converter.BeanOutputConverter;
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
      MessageChatMemoryAdvisor chatMemoryAdvisor,
      ChatLoggingSupport chatLoggingSupport,
      TokenUsageEstimator tokenUsageEstimator,
      TokenUsageMetrics tokenUsageMetrics) {
    ChatProvidersProperties properties = new ChatProvidersProperties();
    properties.setDefaultProvider("stub");

    ChatProvidersProperties.Provider primaryProvider = new ChatProvidersProperties.Provider();
    primaryProvider.setType(ChatProviderType.OPENAI);
    primaryProvider.setDisplayName("Stub Primary");
    primaryProvider.setDefaultModel("stub-model");
    primaryProvider.setTemperature(0.7);
    primaryProvider.setTopP(1.0);
    primaryProvider.setMaxTokens(1024);
    ChatProvidersProperties.Model defaultModel = model("Stub Model", "standard");
    defaultModel.getUsage().setMode(ChatProvidersProperties.UsageMode.NATIVE);
    primaryProvider.getModels().put("stub-model", defaultModel);
    ChatProvidersProperties.Model fastModel = model("Stub Model Fast", "budget");
    fastModel.getUsage().setMode(ChatProvidersProperties.UsageMode.FALLBACK);
    fastModel.getUsage().setFallbackTokenizer("cl100k_base");
    primaryProvider.getModels().put("stub-model-fast", fastModel);

    ChatProvidersProperties.Provider alternateProvider = new ChatProvidersProperties.Provider();
    alternateProvider.setType(ChatProviderType.OPENAI);
    alternateProvider.setDisplayName("Stub Alternate");
    alternateProvider.setDefaultModel("alt-model");
    alternateProvider.setTemperature(0.6);
    alternateProvider.setTopP(0.9);
    alternateProvider.setMaxTokens(2048);
    ChatProvidersProperties.Model altModel = model("Alt Model", "standard");
    altModel.getUsage().setMode(ChatProvidersProperties.UsageMode.NATIVE);
    alternateProvider.getModels().put("alt-model", altModel);
    ChatProvidersProperties.Model altPro = model("Alt Model Pro", "pro");
    altPro.getUsage().setMode(ChatProvidersProperties.UsageMode.FALLBACK);
    altPro.getUsage().setFallbackTokenizer("cl100k_base");
    alternateProvider.getModels().put("alt-model-pro", altPro);
    ChatProvidersProperties.Model syncOnlyModel = model("Alt Model Sync Only", "enterprise");
    syncOnlyModel.setStreamingEnabled(false);
    alternateProvider.getModels().put("alt-model-sync-only", syncOnlyModel);

    properties.getProviders().put("stub", primaryProvider);
    properties.getProviders().put("alternate", alternateProvider);

    ChatProviderRegistry registry = new ChatProviderRegistry(properties);

    ChatClient.Builder chatClientBuilder =
        ChatClient.builder(chatLoggingSupport.decorateModel(new StubChatModel()));
    if (chatMemoryAdvisor != null) {
      chatClientBuilder.defaultAdvisors(chatMemoryAdvisor);
    }
    ChatClient chatClient = chatClientBuilder.build();

    ChatProviderAdapter stubAdapter = adapter("stub", chatClient, primaryProvider);
    ChatProviderAdapter alternateAdapter = adapter("alternate", chatClient, alternateProvider);

    return new ChatProviderService(registry, List.of(stubAdapter, alternateAdapter), tokenUsageEstimator, tokenUsageMetrics);
  }

  private static ChatProvidersProperties.Model model(String displayName, String tier) {
    ChatProvidersProperties.Model model = new ChatProvidersProperties.Model();
    model.setDisplayName(displayName);
    model.setTier(tier);
    return model;
  }

  private static ChatProviderAdapter adapter(
      String providerId, ChatClient chatClient, ChatProvidersProperties.Provider providerConfig) {
    return new ChatProviderAdapter() {
      @Override
      public String providerId() {
        return providerId;
      }

      @Override
      public ChatClient chatClient() {
        return chatClient;
      }

      @Override
      public ChatOptions buildOptions(
          ChatProviderSelection selection, ChatRequestOverrides overrides) {
        return configureOptions(selection, overrides).build();
      }

      @Override
      public ChatOptions buildStreamingOptions(
          ChatProviderSelection selection, ChatRequestOverrides overrides) {
        return configureOptions(selection, overrides).streamUsage(true).build();
      }

      @Override
      public ChatOptions buildStructuredOptions(
          ChatProviderSelection selection,
          ChatRequestOverrides overrides,
          BeanOutputConverter<?> outputConverter) {
        return configureOptions(selection, overrides).build();
      }

      private OpenAiChatOptions.Builder configureOptions(
          ChatProviderSelection selection, ChatRequestOverrides overrides) {
        ChatRequestOverrides effective =
            overrides != null ? overrides : ChatRequestOverrides.empty();
        OpenAiChatOptions.Builder builder = OpenAiChatOptions.builder().model(selection.modelId());

        Double temperature =
            effective.temperature() != null ? effective.temperature() : providerConfig.getTemperature();
        if (temperature != null) {
          builder.temperature(temperature);
        }

        Double topP = effective.topP() != null ? effective.topP() : providerConfig.getTopP();
        if (topP != null) {
          builder.topP(topP);
        }

        Integer maxTokens =
            effective.maxTokens() != null ? effective.maxTokens() : providerConfig.getMaxTokens();
        if (maxTokens != null) {
          builder.maxTokens(maxTokens);
        }

        return builder;
      }
    };
  }

  static class StubChatModel implements ChatModel {

    @Override
    public ChatResponse call(Prompt prompt) {
      StubChatClientState.capturePrompt(prompt);
      StubChatClientState.incrementSyncCall();
      Object override = StubChatClientState.pollSyncResponse();
      if (override instanceof RuntimeException runtimeException) {
        throw runtimeException;
      }
      String aggregated;
      if (override instanceof String customResponse) {
        aggregated = customResponse;
      } else {
        aggregated = String.join("", StubChatClientState.currentTokens());
      }
      Generation generation = new Generation(AssistantMessage.builder().content(aggregated).build());
      Usage usage = StubChatClientState.usage();
      ChatResponseMetadata metadata =
          usage != null ? ChatResponseMetadata.builder().usage(usage).build() : null;
      return metadata != null
          ? new ChatResponse(java.util.List.of(generation), metadata)
          : new ChatResponse(java.util.List.of(generation));
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
      StubChatClientState.capturePrompt(prompt);
      return StubChatClientState.responseFlux();
    }
  }
}
