package com.aiadvent.backend.flow.session.model;

import com.aiadvent.backend.shared.json.AbstractJsonPayload;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;

public final class FlowOverrides extends AbstractJsonPayload {

  private static final FlowOverrides EMPTY = new FlowOverrides(MissingNode.getInstance());

  @JsonCreator
  public FlowOverrides(JsonNode value) {
    super(value);
  }

  public static FlowOverrides empty() {
    return EMPTY;
  }

  public static FlowOverrides from(JsonNode node) {
    if (node == null || node.isMissingNode() || node.isNull()) {
      return empty();
    }
    return new FlowOverrides(node);
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
