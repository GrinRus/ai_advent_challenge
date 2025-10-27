package com.aiadvent.backend.flow.agent.converter;

import com.aiadvent.backend.flow.agent.model.AgentCapabilityPayload;
import com.aiadvent.backend.shared.json.AbstractJsonPayloadAttributeConverter;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Converter;

@Converter(autoApply = false)
public class AgentCapabilityPayloadConverter
    extends AbstractJsonPayloadAttributeConverter<AgentCapabilityPayload> {

  @Override
  protected AgentCapabilityPayload emptyValue() {
    return AgentCapabilityPayload.empty();
  }

  @Override
  protected AgentCapabilityPayload createInstance(JsonNode value) {
    return AgentCapabilityPayload.from(value);
  }
}
