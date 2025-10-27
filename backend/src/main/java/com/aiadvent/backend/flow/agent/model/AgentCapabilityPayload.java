package com.aiadvent.backend.flow.agent.model;

import com.aiadvent.backend.shared.json.AbstractJsonPayload;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;

public final class AgentCapabilityPayload extends AbstractJsonPayload {

  private static final AgentCapabilityPayload EMPTY =
      new AgentCapabilityPayload(MissingNode.getInstance());

  @JsonCreator
  public AgentCapabilityPayload(JsonNode value) {
    super(value);
  }

  public static AgentCapabilityPayload empty() {
    return EMPTY;
  }

  public static AgentCapabilityPayload from(JsonNode node) {
    if (node == null || node.isMissingNode() || node.isNull()) {
      return empty();
    }
    return new AgentCapabilityPayload(node);
  }
}
