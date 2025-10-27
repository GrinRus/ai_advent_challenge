package com.aiadvent.backend.flow.execution.converter;

import com.aiadvent.backend.flow.execution.model.FlowUsagePayload;
import com.aiadvent.backend.shared.json.AbstractJsonPayloadAttributeConverter;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Converter;

@Converter(autoApply = false)
public class FlowUsagePayloadConverter
    extends AbstractJsonPayloadAttributeConverter<FlowUsagePayload> {

  @Override
  protected FlowUsagePayload emptyValue() {
    return FlowUsagePayload.empty();
  }

  @Override
  protected FlowUsagePayload createInstance(JsonNode value) {
    return FlowUsagePayload.from(value);
  }
}
