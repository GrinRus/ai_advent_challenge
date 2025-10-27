package com.aiadvent.backend.chat.memory.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.lang.Nullable;
import org.springframework.util.StringUtils;

/**
 * Lightweight value object that wraps metadata persisted alongside chat memory messages and
 * summaries. Keeps the attributes immutable and centralises JSON (de)serialisation.
 */
public final class ChatMemoryMessageMetadata {

  private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

  private static final ChatMemoryMessageMetadata EMPTY = new ChatMemoryMessageMetadata(Map.of());

  private final Map<String, Object> attributes;

  private ChatMemoryMessageMetadata(Map<String, Object> attributes) {
    if (attributes == null || attributes.isEmpty()) {
      this.attributes = Map.of();
    } else {
      this.attributes = Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
    }
  }

  public static ChatMemoryMessageMetadata empty() {
    return EMPTY;
  }

  public static ChatMemoryMessageMetadata fromMap(@Nullable Map<String, Object> attributes) {
    if (attributes == null || attributes.isEmpty()) {
      return empty();
    }
    return new ChatMemoryMessageMetadata(attributes);
  }

  public static ChatMemoryMessageMetadata fromJson(ObjectMapper objectMapper, @Nullable String json) {
    if (!StringUtils.hasText(json)) {
      return empty();
    }
    try {
      Map<String, Object> raw = objectMapper.readValue(json, MAP_TYPE);
      return fromMap(raw);
    } catch (JsonProcessingException exception) {
      throw new IllegalArgumentException("Failed to deserialize chat memory metadata JSON", exception);
    }
  }

  public Map<String, Object> asMap() {
    return attributes;
  }

  public Builder toBuilder() {
    return new Builder(attributes);
  }

  public String toJson(ObjectMapper objectMapper) {
    if (attributes.isEmpty()) {
      return "{}";
    }
    try {
      return objectMapper.writeValueAsString(attributes);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Failed to serialize chat memory metadata to JSON", exception);
    }
  }

  public static Builder builder() {
    return new Builder(Map.of());
  }

  public static final class Builder {
    private final Map<String, Object> values;

    private Builder(Map<String, Object> seed) {
      this.values = new LinkedHashMap<>(seed != null ? seed : Map.of());
    }

    public Builder put(String key, @Nullable Object value) {
      if (!StringUtils.hasText(key)) {
        return this;
      }
      if (value == null) {
        values.remove(key);
      } else {
        values.put(key, value);
      }
      return this;
    }

    public Builder remove(String key) {
      if (StringUtils.hasText(key)) {
        values.remove(key);
      }
      return this;
    }

    public ChatMemoryMessageMetadata build() {
      return new ChatMemoryMessageMetadata(values);
    }
  }
}
