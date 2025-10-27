package com.aiadvent.backend.flow.api;

import java.util.List;

public record FlowMemoryReferenceResponse(List<MemoryChannel> channels) {

  public record MemoryChannel(String id, String description, boolean required) {}
}
