package com.aiadvent.backend.chat.support;

import jakarta.annotation.PostConstruct;
import javax.sql.DataSource;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.prompt.Prompt;
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
  public ChatClient.Builder chatClientBuilder() {
    return ChatClient.builder(new StubChatModel());
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
