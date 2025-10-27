package com.aiadvent.backend.flow.agent.converter;

import com.aiadvent.backend.flow.agent.model.AgentCostProfile;
import com.aiadvent.backend.shared.json.AbstractJsonPayloadAttributeConverter;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Converter;

@Converter(autoApply = false)
public class AgentCostProfileConverter
    extends AbstractJsonPayloadAttributeConverter<AgentCostProfile> {

  @Override
  protected AgentCostProfile emptyValue() {
    return AgentCostProfile.empty();
  }

  @Override
  protected AgentCostProfile createInstance(JsonNode value) {
    return AgentCostProfile.from(value);
  }
}
