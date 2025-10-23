package com.aiadvent.backend.flow.api;

import com.fasterxml.jackson.databind.JsonNode;

public record FlowStartRequest(JsonNode parameters, JsonNode sharedContext) {}
