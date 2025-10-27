package com.aiadvent.backend.flow.execution.model;

import com.aiadvent.backend.shared.json.AbstractJsonPayload;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;

public final class FlowStepOutputPayload extends AbstractJsonPayload {

  private static final FlowStepOutputPayload EMPTY =
      new FlowStepOutputPayload(MissingNode.getInstance());

  @JsonCreator
  public FlowStepOutputPayload(JsonNode value) {
    super(value);
  }

  public static FlowStepOutputPayload empty() {
    return EMPTY;
  }

  public static FlowStepOutputPayload from(JsonNode node) {
    if (node == null || node.isMissingNode() || node.isNull()) {
      return empty();
    }
    return new FlowStepOutputPayload(node);
  }
}
