package com.aiadvent.backend.chat.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatStreamRequest(
    UUID sessionId,
    @NotBlank String message,
    String provider,
    String model,
    @Valid ChatStreamRequestOptions options) {}
