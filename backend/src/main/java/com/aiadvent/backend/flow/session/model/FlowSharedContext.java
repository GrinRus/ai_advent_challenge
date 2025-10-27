package com.aiadvent.backend.flow.session.model;

import com.aiadvent.backend.shared.json.AbstractJsonPayload;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class FlowSharedContext extends AbstractJsonPayload {

  private static final FlowSharedContext EMPTY = new FlowSharedContext(MissingNode.getInstance());

  @JsonCreator
  public FlowSharedContext(JsonNode value) {
    super(value);
  }

  public static FlowSharedContext empty() {
    return EMPTY;
  }

  public static FlowSharedContext from(JsonNode node) {
    if (node == null || node.isMissingNode() || node.isNull()) {
      return empty();
    }
    return new FlowSharedContext(node);
  }

  public int version() {
    JsonNode node = asJson();
    return node != null ? node.path("version").asInt(0) : 0;
  }

  public String lastStepId() {
    JsonNode node = asJson();
    JsonNode lastStep = node != null ? node.path("lastStepId") : MissingNode.getInstance();
    return lastStep != null && lastStep.isTextual() ? lastStep.asText() : null;
  }

  public List<String> stepKeys(int limit) {
    JsonNode node = asJson();
    if (node == null || !node.has("steps") || !node.get("steps").isObject()) {
      return List.of();
    }
    Set<String> keys = new LinkedHashSet<>();
    node.get("steps")
        .fieldNames()
        .forEachRemaining(
            name -> {
              if (limit <= 0 || keys.size() < limit) {
                keys.add(name);
              }
            });
    return Collections.unmodifiableList(new ArrayList<>(keys));
  }

  public boolean hasLastOutput() {
    JsonNode node = asJson();
    return node != null
        && node.has("lastOutput")
        && !node.get("lastOutput").isMissingNode()
        && !node.get("lastOutput").isNull();
  }
}
