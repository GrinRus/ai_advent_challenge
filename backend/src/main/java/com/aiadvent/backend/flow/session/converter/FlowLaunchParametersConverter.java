package com.aiadvent.backend.flow.session.converter;

import com.aiadvent.backend.flow.session.model.FlowLaunchParameters;
import com.aiadvent.backend.shared.json.AbstractJsonPayloadAttributeConverter;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Converter;

@Converter(autoApply = false)
public class FlowLaunchParametersConverter
    extends AbstractJsonPayloadAttributeConverter<FlowLaunchParameters> {

  @Override
  protected FlowLaunchParameters emptyValue() {
    return FlowLaunchParameters.empty();
  }

  @Override
  protected FlowLaunchParameters createInstance(JsonNode value) {
    return FlowLaunchParameters.from(value);
  }
}
