package com.aiadvent.backend.flow.config;

import java.util.Collections;
import java.util.List;
import java.util.Map;

public class FlowDefinitionDocument {

  private final String startStepId;
  private final Map<String, FlowStepConfig> stepsById;
  private final List<FlowStepConfig> stepsInOrder;
  private final FlowMemoryConfig memoryConfig;

  public FlowDefinitionDocument(
      String startStepId, Map<String, FlowStepConfig> stepsById, FlowMemoryConfig memoryConfig) {
    if (stepsById == null || stepsById.isEmpty()) {
      throw new IllegalArgumentException("Flow definition must contain at least one step");
    }
    this.stepsById = Collections.unmodifiableMap(new java.util.LinkedHashMap<>(stepsById));
    this.stepsInOrder = List.copyOf(this.stepsById.values());
    if (startStepId == null || startStepId.isBlank()) {
      this.startStepId = this.stepsById.keySet().iterator().next();
    } else if (!this.stepsById.containsKey(startStepId)) {
      throw new IllegalArgumentException("Start step '" + startStepId + "' is not defined");
    } else {
      this.startStepId = startStepId;
    }
    this.memoryConfig = memoryConfig != null ? memoryConfig : FlowMemoryConfig.empty();
  }

  public String startStepId() {
    return startStepId;
  }

  public FlowStepConfig step(String stepId) {
    FlowStepConfig config = stepsById.get(stepId);
    if (config == null) {
      throw new IllegalArgumentException("Step not found: " + stepId);
    }
    return config;
  }

  public List<FlowStepConfig> steps() {
    return stepsInOrder;
  }

  public FlowMemoryConfig memoryConfig() {
    return memoryConfig;
  }
}
