package com.aiadvent.backend.flow.agent.converter;

import com.aiadvent.backend.flow.agent.model.AgentDefaultOptions;
import com.aiadvent.backend.shared.json.AbstractJsonPayloadAttributeConverter;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Converter;

@Converter(autoApply = false)
public class AgentDefaultOptionsConverter
    extends AbstractJsonPayloadAttributeConverter<AgentDefaultOptions> {

  @Override
  protected AgentDefaultOptions emptyValue() {
    return AgentDefaultOptions.empty();
  }

  @Override
  protected AgentDefaultOptions createInstance(JsonNode value) {
    return AgentDefaultOptions.from(value);
  }
}
