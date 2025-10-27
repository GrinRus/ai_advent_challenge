package com.aiadvent.backend.flow.agent.converter;

import com.aiadvent.backend.flow.agent.model.AgentToolBindings;
import com.aiadvent.backend.shared.json.AbstractJsonPayloadAttributeConverter;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Converter;

@Converter(autoApply = false)
public class AgentToolBindingsConverter
    extends AbstractJsonPayloadAttributeConverter<AgentToolBindings> {

  @Override
  protected AgentToolBindings emptyValue() {
    return AgentToolBindings.empty();
  }

  @Override
  protected AgentToolBindings createInstance(JsonNode value) {
    return AgentToolBindings.from(value);
  }
}
