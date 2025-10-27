package com.aiadvent.backend.flow.agent.model;

import com.aiadvent.backend.shared.json.AbstractJsonPayload;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;

public final class AgentDefaultOptions extends AbstractJsonPayload {

  private static final AgentDefaultOptions EMPTY = new AgentDefaultOptions(MissingNode.getInstance());

  @JsonCreator
  public AgentDefaultOptions(JsonNode value) {
    super(value);
  }

  public static AgentDefaultOptions empty() {
    return EMPTY;
  }

  public static AgentDefaultOptions from(JsonNode node) {
    if (node == null || node.isMissingNode() || node.isNull()) {
      return empty();
    }
    return new AgentDefaultOptions(node);
  }

  public Double temperature() {
    JsonNode value = asJson().get("temperature");
    return value != null && value.isNumber() ? value.doubleValue() : null;
  }

  public Double topP() {
    JsonNode value = asJson().get("topP");
    return value != null && value.isNumber() ? value.doubleValue() : null;
  }

  public Integer maxTokens() {
    JsonNode value = asJson().get("maxTokens");
    return value != null && value.isInt() ? value.intValue() : null;
  }
}
