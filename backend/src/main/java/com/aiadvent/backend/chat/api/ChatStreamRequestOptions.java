package com.aiadvent.backend.chat.api;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatStreamRequestOptions(Double temperature, Double topP, Integer maxTokens) {}
