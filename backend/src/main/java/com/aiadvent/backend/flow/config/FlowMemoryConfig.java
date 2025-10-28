package com.aiadvent.backend.flow.config;

import java.util.ArrayList;
import java.util.List;

public record FlowMemoryConfig(List<FlowMemoryChannelConfig> sharedChannels) {

  public FlowMemoryConfig {
    List<FlowMemoryChannelConfig> safe =
        sharedChannels != null ? new ArrayList<>(sharedChannels) : new ArrayList<>();
    sharedChannels = List.copyOf(safe);
  }

  public static FlowMemoryConfig empty() {
    return new FlowMemoryConfig(List.of());
  }
}
