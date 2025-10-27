package com.aiadvent.backend.flow.agent.model;

import com.aiadvent.backend.shared.json.AbstractJsonPayload;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;

public final class AgentToolBindings extends AbstractJsonPayload {

  private static final AgentToolBindings EMPTY = new AgentToolBindings(MissingNode.getInstance());

  @JsonCreator
  public AgentToolBindings(JsonNode value) {
    super(value);
  }

  public static AgentToolBindings empty() {
    return EMPTY;
  }

  public static AgentToolBindings from(JsonNode node) {
    if (node == null || node.isMissingNode() || node.isNull()) {
      return empty();
    }
    return new AgentToolBindings(node);
  }
}
