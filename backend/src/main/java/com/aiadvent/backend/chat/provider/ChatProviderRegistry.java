package com.aiadvent.backend.chat.provider;

import com.aiadvent.backend.chat.config.ChatProvidersProperties;
import com.aiadvent.backend.chat.provider.model.ChatProviderSelection;
import java.util.Map;
import java.util.Optional;
import org.springframework.util.StringUtils;

public class ChatProviderRegistry {

  private final ChatProvidersProperties properties;

  public ChatProviderRegistry(ChatProvidersProperties properties) {
    this.properties = properties;
  }

  public ChatProvidersProperties.Provider requireProvider(String providerId) {
    if (!StringUtils.hasText(providerId)) {
      throw new IllegalArgumentException("Provider identifier must be defined");
    }
    ChatProvidersProperties.Provider provider = properties.getProviders().get(providerId);
    if (provider == null) {
      throw new IllegalArgumentException("Unknown provider: " + providerId);
    }
    if (!StringUtils.hasText(provider.getDefaultModel())) {
      throw new IllegalStateException(
          "Default model must be defined for provider '" + providerId + "'");
    }
    return provider;
  }

  public ChatProvidersProperties.Model requireModel(String providerId, String modelId) {
    ChatProvidersProperties.Provider provider = requireProvider(providerId);
    String resolvedModelId = modelId;
    if (!StringUtils.hasText(resolvedModelId)) {
      resolvedModelId = provider.getDefaultModel();
    }
    ChatProvidersProperties.Model model = provider.getModels().get(resolvedModelId);
    if (model == null) {
      throw new IllegalArgumentException(
          "Model '"
              + resolvedModelId
              + "' is not configured for provider '"
              + providerId
              + "'");
    }
    return model;
  }

  public ChatProviderSelection resolveSelection(String requestedProvider, String requestedModel) {
    String providerId =
        Optional.ofNullable(requestedProvider)
            .filter(StringUtils::hasText)
            .orElse(properties.getDefaultProvider());
    if (!StringUtils.hasText(providerId)) {
      throw new IllegalStateException("Default provider must be configured");
    }

    ChatProvidersProperties.Provider provider = requireProvider(providerId);
    String modelId =
        Optional.ofNullable(requestedModel)
            .filter(StringUtils::hasText)
            .orElse(provider.getDefaultModel());

    if (!provider.getModels().containsKey(modelId)) {
      throw new IllegalArgumentException(
          "Model '" + modelId + "' is not available for provider '" + providerId + "'");
    }

    return new ChatProviderSelection(providerId, modelId);
  }

  public Map<String, ChatProvidersProperties.Provider> providers() {
    return properties.getProviders();
  }

  public String defaultProvider() {
    return properties.getDefaultProvider();
  }

  public boolean supportsStreaming(String providerId, String modelId) {
    return requireModel(providerId, modelId).isStreamingEnabled();
  }

  public boolean supportsSync(String providerId, String modelId) {
    return requireModel(providerId, modelId).isSyncEnabled();
  }

  public boolean supportsStructured(String providerId, String modelId) {
    return requireModel(providerId, modelId).isStructuredEnabled();
  }
}
