package com.aiadvent.backend.flow.blueprint;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

@JsonIgnoreProperties(ignoreUnknown = true)
public record FlowBlueprintStep(
    String id,
    String name,
    String agentVersionId,
    String prompt,
    JsonNode overrides,
    JsonNode interaction,
    JsonNode memoryReads,
    JsonNode memoryWrites,
    JsonNode transitions,
    Integer maxAttempts) {
}
