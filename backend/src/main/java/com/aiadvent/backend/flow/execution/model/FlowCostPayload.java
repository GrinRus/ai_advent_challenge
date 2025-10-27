package com.aiadvent.backend.flow.execution.model;

import com.aiadvent.backend.shared.json.AbstractJsonPayload;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;

public final class FlowCostPayload extends AbstractJsonPayload {

  private static final FlowCostPayload EMPTY = new FlowCostPayload(MissingNode.getInstance());

  @JsonCreator
  public FlowCostPayload(JsonNode value) {
    super(value);
  }

  public static FlowCostPayload empty() {
    return EMPTY;
  }

  public static FlowCostPayload from(JsonNode node) {
    if (node == null || node.isMissingNode() || node.isNull()) {
      return empty();
    }
    return new FlowCostPayload(node);
  }
}
