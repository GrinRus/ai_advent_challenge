package com.aiadvent.backend.shared.json;

import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.util.StringUtils;

public abstract class AbstractJsonPayload {

  private final JsonNode value;

  protected AbstractJsonPayload(JsonNode value) {
    this.value = value != null ? value.deepCopy() : MissingNode.getInstance();
  }

  @JsonValue
  public JsonNode json() {
    return value;
  }

  public JsonNode asJson() {
    return value.deepCopy();
  }

  public ObjectNode asObjectNode(ObjectMapper objectMapper) {
    if (value != null && value.isObject()) {
      return (ObjectNode) value.deepCopy();
    }
    return objectMapper.createObjectNode();
  }

  public boolean isEmpty() {
    if (value == null || value.isMissingNode() || value.isNull()) {
      return true;
    }
    if (value.isValueNode()) {
      return !StringUtils.hasText(value.asText());
    }
    return value.isEmpty();
  }
}
