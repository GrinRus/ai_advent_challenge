package com.aiadvent.backend.flow.execution.model;

import com.aiadvent.backend.shared.json.AbstractJsonPayload;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;

public final class FlowEventPayload extends AbstractJsonPayload {

  private static final FlowEventPayload EMPTY = new FlowEventPayload(MissingNode.getInstance());

  @JsonCreator
  public FlowEventPayload(JsonNode value) {
    super(value);
  }

  public static FlowEventPayload empty() {
    return EMPTY;
  }

  public static FlowEventPayload from(JsonNode node) {
    if (node == null || node.isMissingNode() || node.isNull()) {
      return empty();
    }
    return new FlowEventPayload(node);
  }
}
