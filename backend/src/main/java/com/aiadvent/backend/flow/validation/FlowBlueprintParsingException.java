package com.aiadvent.backend.flow.validation;

import com.aiadvent.backend.flow.api.FlowValidationIssue;
import java.util.List;

public class FlowBlueprintParsingException extends RuntimeException {

  private final List<FlowValidationIssue> issues;

  public FlowBlueprintParsingException(List<FlowValidationIssue> issues) {
    super(issues != null && !issues.isEmpty() ? issues.get(0).message() : "Blueprint parsing failed");
    this.issues = issues != null ? List.copyOf(issues) : List.of();
  }

  public static FlowBlueprintParsingException single(FlowValidationIssue issue) {
    return new FlowBlueprintParsingException(List.of(issue));
  }

  public List<FlowValidationIssue> issues() {
    return issues;
  }
}
