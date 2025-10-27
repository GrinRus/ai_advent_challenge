package com.aiadvent.backend.flow.api;

import com.aiadvent.backend.chat.config.ChatProviderType;
import java.util.List;

public record AgentConstructorProvidersResponse(List<Provider> providers) {

  public record Provider(
      String id,
      ChatProviderType type,
      String displayName,
      String description,
      List<Model> models) {}

  public record Model(
      String id,
      String displayName,
      boolean syncEnabled,
      boolean streamingEnabled,
      boolean structuredEnabled,
      Integer contextWindow,
      Integer maxOutputTokens,
      List<String> tags) {}
}

