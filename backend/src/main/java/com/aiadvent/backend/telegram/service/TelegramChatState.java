package com.aiadvent.backend.telegram.service;

import com.aiadvent.backend.chat.api.ChatSyncResponse;
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
    List<String> requestedToolNamespaces,
    Instant updatedAt,
    String lastPrompt,
    ChatSyncResponse lastResponse) {

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
        Instant.now(),
        null,
        null);
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
        requestedToolNamespaces,
        Instant.now(),
        lastPrompt,
        lastResponse);
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
        requestedToolNamespaces,
        Instant.now(),
        lastPrompt,
        lastResponse);
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
        requestedToolNamespaces,
        Instant.now(),
        lastPrompt,
        lastResponse);
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
        requestedToolNamespaces,
        Instant.now(),
        lastPrompt,
        lastResponse);
  }

  public TelegramChatState withRequestedNamespaces(List<String> namespaces) {
    List<String> sanitized =
        namespaces == null || namespaces.isEmpty()
            ? List.of()
            : List.copyOf(new ArrayList<>(namespaces));
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
        Instant.now(),
        lastPrompt,
        lastResponse);
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
        requestedToolNamespaces,
        Instant.now(),
        lastPrompt,
        lastResponse);
  }

  public TelegramChatState toggleNamespace(String namespace) {
    if (!StringUtils.hasText(namespace)) {
      return this;
    }
    String normalized = namespace.trim().toLowerCase();
    List<String> updated = new ArrayList<>(requestedToolNamespaces);
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
        Instant.now(),
        lastPrompt,
        lastResponse);
  }

  public boolean hasNamespace(String namespace) {
    if (!StringUtils.hasText(namespace)) {
      return false;
    }
    String normalized = namespace.trim().toLowerCase();
    return requestedToolNamespaces.stream()
        .anyMatch(existing -> existing.equalsIgnoreCase(normalized));
  }

  public TelegramChatState clearNamespaces() {
    if (requestedToolNamespaces.isEmpty()) {
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
        Instant.now(),
        lastPrompt,
        lastResponse);
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
        requestedToolNamespaces,
        Instant.now(),
        lastPrompt,
        lastResponse);
  }

  public TelegramChatState withLastResult(String prompt, ChatSyncResponse response) {
    return new TelegramChatState(
        chatId,
        sessionId,
        providerId,
        modelId,
        interactionMode,
        temperatureOverride,
        topPOverride,
        maxTokensOverride,
        requestedToolNamespaces,
        Instant.now(),
        prompt,
        response);
  }

  public TelegramChatState clearLastResult() {
    if (lastPrompt == null && lastResponse == null) {
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
        requestedToolNamespaces,
        Instant.now(),
        null,
        null);
  }
}
