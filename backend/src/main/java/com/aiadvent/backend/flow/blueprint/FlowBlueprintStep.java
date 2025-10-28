package com.aiadvent.backend.flow.blueprint;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record FlowBlueprintStep(
    String id,
    String name,
    String agentVersionId,
    String prompt,
    FlowStepOverrides overrides,
    FlowInteractionDraft interaction,
    List<FlowMemoryReadDraft> memoryReads,
    List<FlowMemoryWriteDraft> memoryWrites,
    FlowStepTransitionsDraft transitions,
    Integer maxAttempts) {

  public FlowBlueprintStep {
    prompt = prompt != null ? prompt : "";
    memoryReads = memoryReads != null ? List.copyOf(memoryReads) : List.of();
    memoryWrites = memoryWrites != null ? List.copyOf(memoryWrites) : List.of();
  }
}
