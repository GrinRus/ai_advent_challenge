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

public final class FlowLaunchParameters extends AbstractJsonPayload {

  private static final FlowLaunchParameters EMPTY = new FlowLaunchParameters(MissingNode.getInstance());

  @JsonCreator
  public FlowLaunchParameters(JsonNode value) {
    super(value);
  }

  public static FlowLaunchParameters empty() {
    return EMPTY;
  }

  public static FlowLaunchParameters from(JsonNode node) {
    if (node == null || node.isMissingNode() || node.isNull()) {
      return empty();
    }
    return new FlowLaunchParameters(node);
  }

  public List<String> fieldNames(int limit) {
    JsonNode json = asJson();
    if (json == null || !json.isObject()) {
      return List.of();
    }
    Set<String> names = new LinkedHashSet<>();
    json.fieldNames()
        .forEachRemaining(
            name -> {
              if (limit <= 0 || names.size() < limit) {
                names.add(name);
              }
            });
    return Collections.unmodifiableList(new ArrayList<>(names));
  }
}
