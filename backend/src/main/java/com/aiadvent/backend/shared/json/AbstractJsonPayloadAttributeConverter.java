package com.aiadvent.backend.shared.json;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter
public abstract class AbstractJsonPayloadAttributeConverter<T extends AbstractJsonPayload>
    implements AttributeConverter<T, JsonNode> {

  @Override
  public JsonNode convertToDatabaseColumn(T attribute) {
    if (attribute == null || attribute.isEmpty()) {
      return null;
    }
    return attribute.asJson();
  }

  @Override
  public T convertToEntityAttribute(JsonNode dbData) {
    if (dbData == null || dbData.isNull()) {
      return emptyValue();
    }
    return createInstance(dbData);
  }

  protected abstract T emptyValue();

  protected abstract T createInstance(JsonNode value);
}
