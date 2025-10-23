package com.aiadvent.backend.flow.api;

import com.fasterxml.jackson.databind.JsonNode;

public record AgentCapabilityRequest(String capability, JsonNode payload) {}
