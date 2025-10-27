package com.aiadvent.backend.flow.agent.converter;

import com.aiadvent.backend.flow.agent.options.AgentInvocationOptions;
import com.aiadvent.backend.shared.json.AbstractJacksonJsonAttributeConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = false)
public class AgentInvocationOptionsConverter
    extends AbstractJacksonJsonAttributeConverter<AgentInvocationOptions> {

  public AgentInvocationOptionsConverter() {
    super(AgentInvocationOptions.class);
  }
}

