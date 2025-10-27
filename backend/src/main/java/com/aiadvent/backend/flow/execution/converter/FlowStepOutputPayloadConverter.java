package com.aiadvent.backend.flow.execution.converter;

import com.aiadvent.backend.flow.execution.model.FlowStepOutputPayload;
import com.aiadvent.backend.shared.json.AbstractJsonPayloadAttributeConverter;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Converter;

@Converter(autoApply = false)
public class FlowStepOutputPayloadConverter
    extends AbstractJsonPayloadAttributeConverter<FlowStepOutputPayload> {

  @Override
  protected FlowStepOutputPayload emptyValue() {
    return FlowStepOutputPayload.empty();
  }

  @Override
  protected FlowStepOutputPayload createInstance(JsonNode value) {
    return FlowStepOutputPayload.from(value);
  }
}
