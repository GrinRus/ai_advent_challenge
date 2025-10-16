package com.aiadvent.backend.chat.provider;

import com.aiadvent.backend.chat.config.ChatProvidersProperties;
import com.aiadvent.backend.chat.provider.model.ChatProviderSelection;
import com.aiadvent.backend.chat.provider.model.ChatRequestOverrides;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.converter.BeanOutputConverter;

public class ChatProviderService {

  private final ChatProviderRegistry registry;
  private final Map<String, ChatProviderAdapter> adaptersById;

  public ChatProviderService(ChatProviderRegistry registry, List<ChatProviderAdapter> adapters) {
    this.registry = registry;
    this.adaptersById =
        adapters.stream().collect(Collectors.toUnmodifiableMap(ChatProviderAdapter::providerId, Function.identity()));
  }

  public ChatProviderSelection resolveSelection(String provider, String model) {
    return registry.resolveSelection(provider, model);
  }

  public ChatClient chatClient(String providerId) {
    ChatProviderAdapter adapter = adaptersById.get(providerId);
    if (adapter == null) {
      throw new IllegalArgumentException(
          "No chat client adapter registered for provider '" + providerId + "'");
    }
    return adapter.chatClient();
  }

  public ChatOptions buildOptions(ChatProviderSelection selection, ChatRequestOverrides overrides) {
    ChatProviderAdapter adapter = adaptersById.get(selection.providerId());
    if (adapter == null) {
      throw new IllegalArgumentException(
          "No chat client adapter registered for provider '" + selection.providerId() + "'");
    }
    return adapter.buildOptions(selection, overrides);
  }

  public ChatOptions buildStructuredOptions(
      ChatProviderSelection selection,
      ChatRequestOverrides overrides,
      BeanOutputConverter<?> outputConverter) {
    ChatProviderAdapter adapter = adaptersById.get(selection.providerId());
    if (adapter == null) {
      throw new IllegalArgumentException(
          "No chat client adapter registered for provider '" + selection.providerId() + "'");
    }
    return adapter.buildStructuredOptions(selection, overrides, outputConverter);
  }

  public ChatProvidersProperties.Provider provider(String providerId) {
    return registry.requireProvider(providerId);
  }

  public ChatProvidersProperties.Model model(String providerId, String modelId) {
    return registry.requireModel(providerId, modelId);
  }

  public String defaultProvider() {
    return registry.defaultProvider();
  }

  public Map<String, ChatProvidersProperties.Provider> providers() {
    return Collections.unmodifiableMap(registry.providers());
  }
}
