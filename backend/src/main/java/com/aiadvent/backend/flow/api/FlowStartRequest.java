package com.aiadvent.backend.flow.api;

import com.aiadvent.backend.chat.provider.model.ChatRequestOverrides;
import com.fasterxml.jackson.databind.JsonNode;

public record FlowStartRequest(
    JsonNode parameters, JsonNode sharedContext, ChatRequestOverrides overrides) {}
