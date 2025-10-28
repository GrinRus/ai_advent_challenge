package com.aiadvent.backend.flow.blueprint;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

@JsonIgnoreProperties(ignoreUnknown = true)
public record FlowMemoryWriteDraft(String channel, String mode, JsonNode payload) {}
