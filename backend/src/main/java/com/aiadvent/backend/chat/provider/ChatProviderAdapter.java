package com.aiadvent.backend.chat.provider;

import com.aiadvent.backend.chat.provider.model.ChatProviderSelection;
import com.aiadvent.backend.chat.provider.model.ChatRequestOverrides;
import java.util.Optional;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.tool.ToolCallbackProvider;

public interface ChatProviderAdapter {

  String providerId();

  ChatClient chatClient();

  default ChatClient statelessChatClient() {
    return chatClient();
  }

  ChatOptions buildOptions(ChatProviderSelection selection, ChatRequestOverrides overrides);

  default ChatOptions buildStreamingOptions(
      ChatProviderSelection selection, ChatRequestOverrides overrides) {
    return buildOptions(selection, overrides);
  }

  default ChatOptions buildStructuredOptions(
      ChatProviderSelection selection,
      ChatRequestOverrides overrides,
      BeanOutputConverter<?> outputConverter) {
    return buildOptions(selection, overrides);
  }

  default Optional<ToolCallbackProvider> toolCallbackProvider() {
    return Optional.empty();
  }
}
