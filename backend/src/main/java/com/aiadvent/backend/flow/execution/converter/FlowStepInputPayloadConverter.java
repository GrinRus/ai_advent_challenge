package com.aiadvent.backend.flow.execution.converter;

import com.aiadvent.backend.flow.execution.model.FlowStepInputPayload;
import com.aiadvent.backend.shared.json.AbstractJsonPayloadAttributeConverter;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Converter;

@Converter(autoApply = false)
public class FlowStepInputPayloadConverter
    extends AbstractJsonPayloadAttributeConverter<FlowStepInputPayload> {

  @Override
  protected FlowStepInputPayload emptyValue() {
    return FlowStepInputPayload.empty();
  }

  @Override
  protected FlowStepInputPayload createInstance(JsonNode value) {
    return FlowStepInputPayload.from(value);
  }
}
