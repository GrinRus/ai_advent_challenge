package com.aiadvent.backend.chat.provider;

import com.aiadvent.backend.chat.provider.model.ChatProviderSelection;
import com.aiadvent.backend.chat.provider.model.ChatRequestOverrides;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;

public interface ChatProviderAdapter {

  String providerId();

  ChatClient chatClient();

  ChatOptions buildOptions(ChatProviderSelection selection, ChatRequestOverrides overrides);
}
