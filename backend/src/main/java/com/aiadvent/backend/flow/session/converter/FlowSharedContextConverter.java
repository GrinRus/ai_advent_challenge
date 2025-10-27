package com.aiadvent.backend.flow.session.converter;

import com.aiadvent.backend.flow.session.model.FlowSharedContext;
import com.aiadvent.backend.shared.json.AbstractJsonPayloadAttributeConverter;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Converter;

@Converter(autoApply = false)
public class FlowSharedContextConverter
    extends AbstractJsonPayloadAttributeConverter<FlowSharedContext> {

  @Override
  protected FlowSharedContext emptyValue() {
    return FlowSharedContext.empty();
  }

  @Override
  protected FlowSharedContext createInstance(JsonNode value) {
    return FlowSharedContext.from(value);
  }
}
