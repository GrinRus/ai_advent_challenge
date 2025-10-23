package com.aiadvent.backend.flow.api;

public record AgentDefinitionRequest(
    String identifier,
    String displayName,
    String description,
    Boolean active,
    String createdBy,
    String updatedBy) {}
