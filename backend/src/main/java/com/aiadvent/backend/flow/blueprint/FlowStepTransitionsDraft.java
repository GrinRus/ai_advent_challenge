package com.aiadvent.backend.flow.blueprint;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record FlowStepTransitionsDraft(
    Success onSuccess,
    Failure onFailure) {

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Success(String next, Boolean complete) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Failure(String next, Boolean fail) {}
}
