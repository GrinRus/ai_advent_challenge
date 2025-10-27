package com.aiadvent.backend.flow.blueprint;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

@JsonIgnoreProperties(ignoreUnknown = true)
public record FlowBlueprintLaunchParameter(
    String name,
    String type,
    boolean required,
    String description,
    JsonNode schema,
    JsonNode defaultValue) {

  public FlowBlueprintLaunchParameter {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("Launch parameter name must not be blank");
    }
    type = type != null && !type.isBlank() ? type : "string";
    description = description != null && !description.isBlank() ? description : null;
    required = required;
  }
}

