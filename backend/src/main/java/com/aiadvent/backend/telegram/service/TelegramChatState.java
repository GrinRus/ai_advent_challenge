package com.aiadvent.backend.telegram.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public record TelegramChatState(
    long chatId,
    UUID sessionId,
    String providerId,
    String modelId,
    String interactionMode,
    Double temperatureOverride,
    Double topPOverride,
    Integer maxTokensOverride,
    List<String> requestedToolCodes,
    Instant updatedAt) {

  public static TelegramChatState create(long chatId, String providerId, String modelId) {
    return new TelegramChatState(
        chatId,
        null,
        providerId,
        modelId,
        "default",
        null,
        null,
        null,
        List.of(),
        Instant.now());
  }

  public TelegramChatState withSessionId(UUID nextSessionId) {
    return new TelegramChatState(
        chatId,
        nextSessionId,
        providerId,
        modelId,
        interactionMode,
        temperatureOverride,
        topPOverride,
        maxTokensOverride,
        requestedToolCodes,
        Instant.now());
  }

  public TelegramChatState resetSession() {
    return new TelegramChatState(
        chatId,
        null,
        providerId,
        modelId,
        interactionMode,
        temperatureOverride,
        topPOverride,
        maxTokensOverride,
        requestedToolCodes,
        Instant.now());
  }

  public TelegramChatState withInteractionMode(String mode) {
    String normalized = mode != null ? mode.trim().toLowerCase() : "default";
    return new TelegramChatState(
        chatId,
        sessionId,
        providerId,
        modelId,
        normalized,
        temperatureOverride,
        topPOverride,
        maxTokensOverride,
        requestedToolCodes,
        Instant.now());
  }

  public TelegramChatState withProviderAndModel(String provider, String model) {
    return new TelegramChatState(
        chatId,
        sessionId,
        provider,
        model,
        interactionMode,
        temperatureOverride,
        topPOverride,
        maxTokensOverride,
        requestedToolCodes,
        Instant.now());
  }

  public TelegramChatState withRequestedToolCodes(List<String> toolCodes) {
    List<String> sanitized =
        toolCodes == null || toolCodes.isEmpty()
            ? List.of()
            : List.copyOf(new ArrayList<>(toolCodes));
    return new TelegramChatState(
        chatId,
        sessionId,
        providerId,
        modelId,
        interactionMode,
        temperatureOverride,
        topPOverride,
        maxTokensOverride,
        sanitized,
        Instant.now());
  }

  public TelegramChatState withSamplingOverrides(
      Double temperature, Double topP, Integer maxTokens) {
    return new TelegramChatState(
        chatId,
        sessionId,
        providerId,
        modelId,
        interactionMode,
        temperature,
        topP,
        maxTokens,
        requestedToolCodes,
        Instant.now());
  }

  public boolean hasSamplingOverrides() {
    return temperatureOverride != null || topPOverride != null || maxTokensOverride != null;
  }
}

