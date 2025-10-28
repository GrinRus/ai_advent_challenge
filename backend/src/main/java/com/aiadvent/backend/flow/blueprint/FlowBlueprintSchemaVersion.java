package com.aiadvent.backend.flow.blueprint;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

public final class FlowBlueprintSchemaVersion {

  public static final int MIN_SUPPORTED = 1;
  public static final int CURRENT = 2;

  private FlowBlueprintSchemaVersion() {
    throw new AssertionError("Utility class");
  }

  public static int normalize(Integer version) {
    if (version == null || version <= 0) {
      return MIN_SUPPORTED;
    }
    return version;
  }

  public static boolean isSupported(int version) {
    return version >= MIN_SUPPORTED && version <= CURRENT;
  }

  public static FlowBlueprint upgradeToCurrent(FlowBlueprint blueprint, ObjectMapper objectMapper) {
    if (blueprint == null) {
      return null;
    }
    int version = normalize(blueprint.schemaVersion());
    if (version >= CURRENT) {
      return blueprint;
    }
    try {
      ObjectNode node = objectMapper.valueToTree(blueprint);
      node.put("schemaVersion", CURRENT);
      return objectMapper.treeToValue(node, FlowBlueprint.class);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Failed to upgrade blueprint schema version", exception);
    }
  }
}
