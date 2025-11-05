package com.aiadvent.backend.telegram.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.util.StringUtils;

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

  public TelegramChatState toggleToolCode(String code) {
    if (!StringUtils.hasText(code)) {
      return this;
    }
    String normalized = code.trim().toLowerCase();
    List<String> updated = new ArrayList<>(requestedToolCodes);
    if (updated.stream().anyMatch(existing -> existing.equalsIgnoreCase(normalized))) {
      updated.removeIf(existing -> existing.equalsIgnoreCase(normalized));
    } else {
      updated.add(normalized);
    }
    return new TelegramChatState(
        chatId,
        sessionId,
        providerId,
        modelId,
        interactionMode,
        temperatureOverride,
        topPOverride,
        maxTokensOverride,
        List.copyOf(updated),
        Instant.now());
  }

  public boolean hasToolCode(String code) {
    if (!StringUtils.hasText(code)) {
      return false;
    }
    String normalized = code.trim().toLowerCase();
    return requestedToolCodes.stream().anyMatch(existing -> existing.equalsIgnoreCase(normalized));
  }

  public TelegramChatState clearToolCodes() {
    if (requestedToolCodes.isEmpty()) {
      return this;
    }
    return new TelegramChatState(
        chatId,
        sessionId,
        providerId,
        modelId,
        interactionMode,
        temperatureOverride,
        topPOverride,
        maxTokensOverride,
        List.of(),
        Instant.now());
  }

  public boolean hasSamplingOverrides() {
    return temperatureOverride != null || topPOverride != null || maxTokensOverride != null;
  }

  public TelegramChatState resetSamplingOverrides() {
    if (!hasSamplingOverrides()) {
      return this;
    }
    return new TelegramChatState(
        chatId,
        sessionId,
        providerId,
        modelId,
        interactionMode,
        null,
        null,
        null,
        requestedToolCodes,
        Instant.now());
  }
}
