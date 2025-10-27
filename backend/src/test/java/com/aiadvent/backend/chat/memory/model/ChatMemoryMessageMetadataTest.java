package com.aiadvent.backend.chat.memory.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ChatMemoryMessageMetadataTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void fromMapCreatesImmutableCopy() {
    ChatMemoryMessageMetadata metadata =
        ChatMemoryMessageMetadata.fromMap(Map.of("foo", "bar", "number", 42));

    assertThat(metadata.asMap()).containsEntry("foo", "bar").containsEntry("number", 42);
    assertThat(metadata.asMap()).isUnmodifiable();
  }

  @Test
  void toJsonAndBackRoundTrip() {
    ChatMemoryMessageMetadata metadata =
        ChatMemoryMessageMetadata.builder().put("a", 1).put("summary", true).build();

    String json = metadata.toJson(objectMapper);
    ChatMemoryMessageMetadata restored = ChatMemoryMessageMetadata.fromJson(objectMapper, json);

    assertThat(restored.asMap()).isEqualTo(metadata.asMap());
  }

  @Test
  void builderInheritsExistingValues() {
    ChatMemoryMessageMetadata metadata =
        ChatMemoryMessageMetadata.builder().put("summary", true).build();

    ChatMemoryMessageMetadata mutated =
        metadata.toBuilder().put("language", "en").put("summary", false).build();

    assertThat(mutated.asMap()).containsEntry("language", "en").containsEntry("summary", false);
  }

  @Test
  void fromJsonThrowsOnInvalidPayload() {
    assertThatThrownBy(() -> ChatMemoryMessageMetadata.fromJson(objectMapper, "{invalid"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
