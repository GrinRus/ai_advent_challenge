package com.aiadvent.backend.flow.session.converter;

import com.aiadvent.backend.flow.session.model.FlowOverrides;
import com.aiadvent.backend.shared.json.AbstractJsonPayloadAttributeConverter;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Converter;

@Converter(autoApply = false)
public class FlowOverridesConverter
    extends AbstractJsonPayloadAttributeConverter<FlowOverrides> {

  @Override
  protected FlowOverrides emptyValue() {
    return FlowOverrides.empty();
  }

  @Override
  protected FlowOverrides createInstance(JsonNode value) {
    return FlowOverrides.from(value);
  }
}
