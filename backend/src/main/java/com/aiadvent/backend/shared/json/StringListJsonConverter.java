package com.aiadvent.backend.shared.json;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.Collections;
import java.util.List;

@Converter
public class StringListJsonConverter implements AttributeConverter<List<String>, JsonNode> {

  private static final TypeReference<List<String>> TYPE = new TypeReference<>() {};
  private final JsonMapper mapper =
      JsonMapper.builder().findAndAddModules().enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS).build();

  @Override
  public JsonNode convertToDatabaseColumn(List<String> attribute) {
    if (attribute == null || attribute.isEmpty()) {
      return mapper.nullNode();
    }
    return mapper.valueToTree(attribute);
  }

  @Override
  public List<String> convertToEntityAttribute(JsonNode dbData) {
    if (dbData == null || dbData.isNull() || dbData.isMissingNode()) {
      return List.of();
    }
    try {
      List<String> values = mapper.treeToValue(dbData, TYPE);
      if (values == null || values.isEmpty()) {
        return List.of();
      }
      return Collections.unmodifiableList(values);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Failed to convert JSON to List<String>", exception);
    }
  }
}

