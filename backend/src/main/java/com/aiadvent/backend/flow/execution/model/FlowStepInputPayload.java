package com.aiadvent.backend.flow.execution.model;

import com.aiadvent.backend.shared.json.AbstractJsonPayload;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;

public final class FlowStepInputPayload extends AbstractJsonPayload {

  private static final FlowStepInputPayload EMPTY =
      new FlowStepInputPayload(MissingNode.getInstance());

  @JsonCreator
  public FlowStepInputPayload(JsonNode value) {
    super(value);
  }

  public static FlowStepInputPayload empty() {
    return EMPTY;
  }

  public static FlowStepInputPayload from(JsonNode node) {
    if (node == null || node.isMissingNode() || node.isNull()) {
      return empty();
    }
    return new FlowStepInputPayload(node);
  }
}
