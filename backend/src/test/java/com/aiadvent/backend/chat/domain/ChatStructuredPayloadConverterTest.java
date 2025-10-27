package com.aiadvent.backend.chat.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.aiadvent.backend.chat.domain.converter.ChatStructuredPayloadConverter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

class ChatStructuredPayloadConverterTest {

  private final ChatStructuredPayloadConverter converter = new ChatStructuredPayloadConverter();
  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void convertToEntityAttributeReturnsEmptyForNull() {
    ChatStructuredPayload payload = converter.convertToEntityAttribute(null);
    assertThat(payload).isNotNull();
    assertThat(payload.isEmpty()).isTrue();
  }

  @Test
  void roundTripPreservesPayload() {
    ObjectNode node = objectMapper.createObjectNode();
    node.put("status", "ok");
    node.putObject("answer").put("summary", "data");

    ChatStructuredPayload original = ChatStructuredPayload.from(node);

    var dbValue = converter.convertToDatabaseColumn(original);
    ChatStructuredPayload restored = converter.convertToEntityAttribute(dbValue);

    assertThat(restored.isEmpty()).isFalse();
    assertThat(restored.asJson()).isEqualTo(node);
  }
}
