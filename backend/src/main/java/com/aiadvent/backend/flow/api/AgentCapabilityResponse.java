package com.aiadvent.backend.flow.api;

import com.fasterxml.jackson.databind.JsonNode;

public record AgentCapabilityResponse(String capability, JsonNode payload) {}
