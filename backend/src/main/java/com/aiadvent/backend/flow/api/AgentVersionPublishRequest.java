package com.aiadvent.backend.flow.api;

import java.util.List;

public record AgentVersionPublishRequest(String updatedBy, List<AgentCapabilityRequest> capabilities) {}
