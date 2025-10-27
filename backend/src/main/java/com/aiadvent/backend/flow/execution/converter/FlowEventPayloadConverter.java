package com.aiadvent.backend.flow.execution.converter;

import com.aiadvent.backend.flow.execution.model.FlowEventPayload;
import com.aiadvent.backend.shared.json.AbstractJsonPayloadAttributeConverter;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Converter;

@Converter(autoApply = false)
public class FlowEventPayloadConverter
    extends AbstractJsonPayloadAttributeConverter<FlowEventPayload> {

  @Override
  protected FlowEventPayload emptyValue() {
    return FlowEventPayload.empty();
  }

  @Override
  protected FlowEventPayload createInstance(JsonNode value) {
    return FlowEventPayload.from(value);
  }
}
