package com.aiadvent.backend.chat.domain.converter;

import com.aiadvent.backend.chat.domain.ChatStructuredPayload;
import com.aiadvent.backend.shared.json.AbstractJsonPayloadAttributeConverter;
import com.fasterxml.jackson.databind.JsonNode;

public class ChatStructuredPayloadConverter
    extends AbstractJsonPayloadAttributeConverter<ChatStructuredPayload> {

  @Override
  protected ChatStructuredPayload emptyValue() {
    return ChatStructuredPayload.empty();
  }

  @Override
  protected ChatStructuredPayload createInstance(JsonNode value) {
    return ChatStructuredPayload.from(value);
  }
}
