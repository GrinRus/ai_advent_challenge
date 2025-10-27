package com.aiadvent.backend.flow.agent.model;

import com.aiadvent.backend.shared.json.AbstractJsonPayload;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;

public final class AgentCostProfile extends AbstractJsonPayload {

  private static final AgentCostProfile EMPTY = new AgentCostProfile(MissingNode.getInstance());

  @JsonCreator
  public AgentCostProfile(JsonNode value) {
    super(value);
  }

  public static AgentCostProfile empty() {
    return EMPTY;
  }

  public static AgentCostProfile from(JsonNode node) {
    if (node == null || node.isMissingNode() || node.isNull()) {
      return empty();
    }
    return new AgentCostProfile(node);
  }
}
