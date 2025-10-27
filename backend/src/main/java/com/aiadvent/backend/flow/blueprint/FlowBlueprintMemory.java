package com.aiadvent.backend.flow.blueprint;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.ArrayList;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record FlowBlueprintMemory(List<FlowBlueprintMemoryChannel> sharedChannels) {

  public FlowBlueprintMemory {
    List<FlowBlueprintMemoryChannel> safeChannels =
        sharedChannels != null ? new ArrayList<>(sharedChannels) : new ArrayList<>();
    sharedChannels = List.copyOf(safeChannels);
  }

  public static FlowBlueprintMemory empty() {
    return new FlowBlueprintMemory(List.of());
  }

  @Override
  public List<FlowBlueprintMemoryChannel> sharedChannels() {
    return sharedChannels != null ? sharedChannels : List.of();
  }
}

