package com.aiadvent.backend.flow.session.model;

import com.aiadvent.backend.shared.json.AbstractJsonPayload;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;

public final class FlowTelemetrySnapshot extends AbstractJsonPayload {

  private static final FlowTelemetrySnapshot EMPTY =
      new FlowTelemetrySnapshot(MissingNode.getInstance());

  @JsonCreator
  public FlowTelemetrySnapshot(JsonNode value) {
    super(value);
  }

  public static FlowTelemetrySnapshot empty() {
    return EMPTY;
  }

  public static FlowTelemetrySnapshot from(JsonNode node) {
    if (node == null || node.isMissingNode() || node.isNull()) {
      return empty();
    }
    return new FlowTelemetrySnapshot(node);
  }
}
