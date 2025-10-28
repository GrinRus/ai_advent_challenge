package com.aiadvent.backend.flow.blueprint;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record FlowStepOverrides(Double temperature, Double topP, Integer maxTokens) {

  public boolean isEmpty() {
    return temperature == null && topP == null && maxTokens == null;
  }
}
