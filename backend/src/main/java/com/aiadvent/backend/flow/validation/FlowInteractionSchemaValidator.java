package com.aiadvent.backend.flow.validation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class FlowInteractionSchemaValidator {

  private static final Set<String> STRING_FORMATS =
      Set.of("textarea", "date", "date-time", "binary", "json", "radio");
  private static final Set<String> BOOLEAN_FORMATS = Set.of("toggle");
  private static final Set<String> EXTENDED_STRING_FORMATS = Set.of("uuid", "email", "currency");
  private static final ObjectMapper MAPPER = new ObjectMapper();

  public void validateSchema(JsonNode schema) {
    if (schema == null || schema.isNull()) {
      return;
    }
    if (!schema.isObject()) {
      throw new IllegalArgumentException("payloadSchema must be an object");
    }
    validateSchemaNode(schema, "$");
  }

  private void validateSchemaNode(JsonNode schema, String path) {
    String type = textValue(schema, "type");
    if (type == null || type.isBlank()) {
      throw new IllegalArgumentException("Schema node " + path + " must declare 'type'");
    }

    switch (type) {
      case "object" -> validateObjectSchema(schema, path);
      case "array" -> validateArraySchema(schema, path);
      case "string" -> validateStringSchema(schema, path);
      case "number" -> validateNumberSchema(schema, path);
      case "boolean" -> validateBooleanSchema(schema, path);
      default -> throw new IllegalArgumentException("Unsupported schema type '" + type + "' at " + path);
    }
  }

  private void validateObjectSchema(JsonNode schema, String path) {
    JsonNode properties = schema.get("properties");
    if (properties != null && !properties.isNull()) {
      if (!properties.isObject()) {
        throw new IllegalArgumentException("'properties' must be an object at " + path);
      }
      Iterator<Map.Entry<String, JsonNode>> fields = properties.fields();
      while (fields.hasNext()) {
        Map.Entry<String, JsonNode> entry = fields.next();
        validateSchemaNode(entry.getValue(), path + "." + entry.getKey());
      }
    }

    JsonNode requiredNode = schema.get("required");
    if (requiredNode != null && !requiredNode.isNull()) {
      if (!requiredNode.isArray()) {
        throw new IllegalArgumentException("'required' must be an array at " + path);
      }
      for (JsonNode item : requiredNode) {
        if (!item.isTextual()) {
          throw new IllegalArgumentException("'required' entries must be strings at " + path);
        }
      }
    }
  }

  private void validateArraySchema(JsonNode schema, String path) {
    JsonNode items = schema.get("items");
    if (items == null || items.isNull()) {
      throw new IllegalArgumentException("'items' must be provided for array schema at " + path);
    }
    validateSchemaNode(items, path + "[*]");

    JsonNode enumNode = items.get("enum");
    if (enumNode != null && !enumNode.isNull()) {
      validateEnum(enumNode, path + "[enum]");
    }
  }

  private void validateStringSchema(JsonNode schema, String path) {
    String format = textValue(schema, "format");
    if (format != null
        && !STRING_FORMATS.contains(format)
        && !EXTENDED_STRING_FORMATS.contains(format)) {
      throw new IllegalArgumentException(
          "Unsupported string format '" + format + "' at " + path);
    }
    JsonNode enumNode = schema.get("enum");
    if (enumNode != null && !enumNode.isNull()) {
      validateEnum(enumNode, path);
    }
  }

  private void validateNumberSchema(JsonNode schema, String path) {
    JsonNode minimum = schema.get("minimum");
    if (minimum != null && !minimum.isNull() && !minimum.isNumber()) {
      throw new IllegalArgumentException("'minimum' must be numeric at " + path);
    }
    JsonNode maximum = schema.get("maximum");
    if (maximum != null && !maximum.isNull() && !maximum.isNumber()) {
      throw new IllegalArgumentException("'maximum' must be numeric at " + path);
    }
  }

  private void validateBooleanSchema(JsonNode schema, String path) {
    String format = textValue(schema, "format");
    if (format != null && !BOOLEAN_FORMATS.contains(format)) {
      throw new IllegalArgumentException(
          "Unsupported boolean format '" + format + "' at " + path);
    }
  }

  public void validatePayload(JsonNode schema, JsonNode payload) {
    if (schema == null || schema.isNull()) {
      return;
    }
    validatePayloadNode(schema, payload, "$");
  }

  private void validatePayloadNode(JsonNode schema, JsonNode payload, String path) {
    String type = textValue(schema, "type");
    if (type == null || type.isBlank()) {
      return;
    }

    switch (type) {
      case "object" -> validateObjectPayload(schema, payload, path);
      case "array" -> validateArrayPayload(schema, payload, path);
      case "string" -> validateStringPayload(schema, payload, path);
      case "number" -> validateNumberPayload(schema, payload, path);
      case "boolean" -> validateBooleanPayload(schema, payload, path);
      default -> throw new IllegalArgumentException("Unsupported schema type '" + type + "' at " + path);
    }
  }

  private void validateObjectPayload(JsonNode schema, JsonNode payload, String path) {
    if (payload == null || payload.isNull()) {
      if (hasRequired(schema)) {
        throw new IllegalArgumentException("Value required at " + path);
      }
      return;
    }
    if (!payload.isObject()) {
      throw new IllegalArgumentException("Expected object payload at " + path);
    }
    ObjectNode payloadObject = (ObjectNode) payload;
    Set<String> required = readRequired(schema);
    JsonNode properties = schema.get("properties");
    if (properties != null && properties.isObject()) {
      Iterator<Map.Entry<String, JsonNode>> fields = properties.fields();
      while (fields.hasNext()) {
        Map.Entry<String, JsonNode> entry = fields.next();
        String propertyName = entry.getKey();
        JsonNode propertySchema = entry.getValue();
        JsonNode propertyValue =
            payloadObject.has(propertyName) ? payloadObject.get(propertyName) : null;
        if ((propertyValue == null || propertyValue.isNull()) && required.contains(propertyName)) {
          throw new IllegalArgumentException("Value required for property '" + propertyName + "' at " + path);
        }
        validatePayloadNode(propertySchema, propertyValue, path + "." + propertyName);
      }
    } else if (!required.isEmpty()) {
      throw new IllegalArgumentException("Object schema at " + path + " must declare 'properties'");
    }
  }

  private void validateArrayPayload(JsonNode schema, JsonNode payload, String path) {
    if (payload == null || payload.isNull()) {
      return;
    }
    if (!payload.isArray()) {
      throw new IllegalArgumentException("Expected array payload at " + path);
    }
    ArrayNode arrayNode = (ArrayNode) payload;
    JsonNode itemsSchema = schema.get("items");
    if (itemsSchema == null || itemsSchema.isNull()) {
      return;
    }
    for (int i = 0; i < arrayNode.size(); i++) {
      JsonNode element = arrayNode.get(i);
      validatePayloadNode(itemsSchema, element, path + "[" + i + "]");
    }
  }

  private void validateStringPayload(JsonNode schema, JsonNode payload, String path) {
    if (payload == null || payload.isNull()) {
      return;
    }
    if (!payload.isTextual()) {
      throw new IllegalArgumentException("Expected string payload at " + path);
    }
    String value = payload.asText();
    String format = textValue(schema, "format");
    if ("date".equals(format)) {
      try {
        LocalDate.parse(value);
      } catch (DateTimeParseException exception) {
        throw new IllegalArgumentException("Invalid date value at " + path, exception);
      }
    } else if ("date-time".equals(format)) {
      try {
        OffsetDateTime.parse(value);
      } catch (DateTimeParseException exception) {
        throw new IllegalArgumentException("Invalid date-time value at " + path, exception);
      }
    } else if ("json".equals(format)) {
      try {
        MAPPER.readTree(value);
      } catch (JsonProcessingException exception) {
        throw new IllegalArgumentException("Invalid JSON payload at " + path, exception);
      }
    }

    JsonNode enumNode = schema.get("enum");
    if (enumNode != null && enumNode.isArray()) {
      Set<String> allowed = toEnumSet(enumNode);
      if (!allowed.contains(value)) {
        throw new IllegalArgumentException("Value '" + value + "' is not allowed at " + path);
      }
    }
  }

  private void validateNumberPayload(JsonNode schema, JsonNode payload, String path) {
    if (payload == null || payload.isNull()) {
      return;
    }
    if (!payload.isNumber()) {
      throw new IllegalArgumentException("Expected numeric payload at " + path);
    }
    double number = payload.asDouble();
    JsonNode minimum = schema.get("minimum");
    if (minimum != null && minimum.isNumber() && number < minimum.asDouble()) {
      throw new IllegalArgumentException("Value at " + path + " must be >= " + minimum.asDouble());
    }
    JsonNode maximum = schema.get("maximum");
    if (maximum != null && maximum.isNumber() && number > maximum.asDouble()) {
      throw new IllegalArgumentException("Value at " + path + " must be <= " + maximum.asDouble());
    }
  }

  private void validateBooleanPayload(JsonNode schema, JsonNode payload, String path) {
    if (payload == null || payload.isNull()) {
      return;
    }
    if (!payload.isBoolean()) {
      throw new IllegalArgumentException("Expected boolean payload at " + path);
    }
  }

  private boolean hasRequired(JsonNode schema) {
    JsonNode required = schema.get("required");
    return required != null && required.isArray() && required.size() > 0;
  }

  private Set<String> readRequired(JsonNode schema) {
    JsonNode required = schema.get("required");
    if (required == null || !required.isArray()) {
      return Set.of();
    }
    Set<String> values = new HashSet<>();
    for (JsonNode item : required) {
      if (item.isTextual()) {
        values.add(item.asText());
      }
    }
    return values;
  }

  private void validateEnum(JsonNode enumNode, String path) {
    if (!enumNode.isArray()) {
      throw new IllegalArgumentException("'enum' must be an array at " + path);
    }
    for (JsonNode value : enumNode) {
      if (!value.isTextual()) {
        throw new IllegalArgumentException("enum values must be strings at " + path);
      }
    }
  }

  private Set<String> toEnumSet(JsonNode enumNode) {
    Set<String> values = new HashSet<>();
    if (enumNode != null && enumNode.isArray()) {
      for (JsonNode value : enumNode) {
        if (value.isTextual()) {
          values.add(value.asText());
        }
      }
    }
    return values;
  }

  private String textValue(JsonNode node, String field) {
    JsonNode value = node.get(field);
    return value != null && value.isTextual() ? value.asText() : null;
  }
}
