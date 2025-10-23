package com.aiadvent.backend.flow.memory;

import com.aiadvent.backend.flow.domain.FlowMemoryVersion;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;

public interface MemoryChannelWriter {

  FlowMemoryVersion append(UUID flowSessionId, String channel, Object payload, UUID createdByStepId);

  FlowMemoryVersion append(UUID flowSessionId, String channel, JsonNode payload, UUID createdByStepId);
}
