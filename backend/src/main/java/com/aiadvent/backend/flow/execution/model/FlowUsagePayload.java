package com.aiadvent.backend.flow.execution.model;

import com.aiadvent.backend.shared.json.AbstractJsonPayload;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;

public final class FlowUsagePayload extends AbstractJsonPayload {

  private static final FlowUsagePayload EMPTY = new FlowUsagePayload(MissingNode.getInstance());

  @JsonCreator
  public FlowUsagePayload(JsonNode value) {
    super(value);
  }

  public static FlowUsagePayload empty() {
    return EMPTY;
  }

  public static FlowUsagePayload from(JsonNode node) {
    if (node == null || node.isMissingNode() || node.isNull()) {
      return empty();
    }
    return new FlowUsagePayload(node);
  }
}
