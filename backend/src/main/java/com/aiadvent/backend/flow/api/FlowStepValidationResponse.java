package com.aiadvent.backend.flow.api;

import java.util.List;

public record FlowStepValidationResponse(
    boolean valid, List<FlowValidationIssue> errors, List<FlowValidationIssue> warnings) {

  public static FlowStepValidationResponse ok() {
    return new FlowStepValidationResponse(true, List.of(), List.of());
  }

  public static FlowStepValidationResponse withIssues(
      List<FlowValidationIssue> errors, List<FlowValidationIssue> warnings) {
    boolean valid = errors == null || errors.isEmpty();
    List<FlowValidationIssue> safeErrors = errors != null ? List.copyOf(errors) : List.of();
    List<FlowValidationIssue> safeWarnings =
        warnings != null ? List.copyOf(warnings) : List.of();
    return new FlowStepValidationResponse(valid, safeErrors, safeWarnings);
  }
}
