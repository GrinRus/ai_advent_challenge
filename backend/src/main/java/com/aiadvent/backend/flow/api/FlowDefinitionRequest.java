package com.aiadvent.backend.flow.api;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;

public record FlowDefinitionRequest(
    String name,
    String description,
    String updatedBy,
    JsonNode definition,
    String changeNotes,
    UUID sourceDefinitionId) {}
