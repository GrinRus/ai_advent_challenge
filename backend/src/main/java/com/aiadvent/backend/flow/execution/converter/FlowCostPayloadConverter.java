package com.aiadvent.backend.flow.execution.converter;

import com.aiadvent.backend.flow.execution.model.FlowCostPayload;
import com.aiadvent.backend.shared.json.AbstractJsonPayloadAttributeConverter;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Converter;

@Converter(autoApply = false)
public class FlowCostPayloadConverter
    extends AbstractJsonPayloadAttributeConverter<FlowCostPayload> {

  @Override
  protected FlowCostPayload emptyValue() {
    return FlowCostPayload.empty();
  }

  @Override
  protected FlowCostPayload createInstance(JsonNode value) {
    return FlowCostPayload.from(value);
  }
}
