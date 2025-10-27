package com.aiadvent.backend.flow.blueprint;

import com.aiadvent.backend.shared.json.AbstractJacksonJsonAttributeConverter;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Converter;

@Converter(autoApply = false)
public class FlowBlueprintConverter extends AbstractJacksonJsonAttributeConverter<FlowBlueprint> {

  public FlowBlueprintConverter() {
    super(FlowBlueprint.class);
  }

  @Override
  public JsonNode convertToDatabaseColumn(FlowBlueprint attribute) {
    return super.convertToDatabaseColumn(attribute);
  }

  @Override
  public FlowBlueprint convertToEntityAttribute(JsonNode dbData) {
    try {
      FlowBlueprint blueprint = super.convertToEntityAttribute(dbData);
      if (blueprint == null) {
        throw new IllegalStateException("Flow blueprint JSON payload must not be null");
      }
      return blueprint;
    } catch (RuntimeException exception) {
      throw new IllegalStateException("Failed to convert flow blueprint JSON: " + dbData, exception);
    }
  }
}
