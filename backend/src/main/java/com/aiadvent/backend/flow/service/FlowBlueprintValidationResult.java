package com.aiadvent.backend.flow.service;

import com.aiadvent.backend.flow.api.FlowValidationIssue;
import com.aiadvent.backend.flow.config.FlowDefinitionDocument;
import java.util.List;

public record FlowBlueprintValidationResult(
    FlowDefinitionDocument document, List<FlowValidationIssue> errors, List<FlowValidationIssue> warnings) {

  public boolean isValid() {
    return errors == null || errors.isEmpty();
  }
}
