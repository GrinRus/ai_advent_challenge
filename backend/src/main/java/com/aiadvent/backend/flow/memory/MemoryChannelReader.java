package com.aiadvent.backend.flow.memory;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MemoryChannelReader {

  Optional<JsonNode> read(UUID flowSessionId, String channel);

  Optional<JsonNode> read(UUID flowSessionId, String channel, long version);

  List<JsonNode> history(UUID flowSessionId, String channel, int limit);
}
