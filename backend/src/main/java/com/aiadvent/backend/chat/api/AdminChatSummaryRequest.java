package com.aiadvent.backend.chat.api;

import jakarta.validation.constraints.NotNull;

public record AdminChatSummaryRequest(@NotNull String providerId, @NotNull String modelId) {}
