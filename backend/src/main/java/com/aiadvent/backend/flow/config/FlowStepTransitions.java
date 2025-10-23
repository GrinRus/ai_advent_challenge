package com.aiadvent.backend.flow.config;

public record FlowStepTransitions(
    String onSuccess, boolean completeOnSuccess, String onFailure, boolean failFlowOnFailure) {

  public FlowStepTransitions {
    completeOnSuccess = completeOnSuccess || (onSuccess == null || onSuccess.isBlank());
  }

  public static FlowStepTransitions defaults() {
    return new FlowStepTransitions(null, true, null, true);
  }
}
