package com.aiadvent.backend.shared.json;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter
public abstract class AbstractJacksonJsonAttributeConverter<T> implements AttributeConverter<T, JsonNode> {

  private final JsonMapper objectMapper;
  private final Class<T> type;

  protected AbstractJacksonJsonAttributeConverter(Class<T> type) {
    this.type = type;
    this.objectMapper =
        JsonMapper.builder().findAndAddModules().enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS).build();
  }

  @Override
  public JsonNode convertToDatabaseColumn(T attribute) {
    if (attribute == null) {
      return null;
    }
    return objectMapper.valueToTree(attribute);
  }

  @Override
  public T convertToEntityAttribute(JsonNode dbData) {
    if (dbData == null || dbData.isNull()) {
      return null;
    }
    try {
      return objectMapper.treeToValue(dbData, type);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Failed to convert JSON to " + type.getSimpleName(), exception);
    }
  }
}
