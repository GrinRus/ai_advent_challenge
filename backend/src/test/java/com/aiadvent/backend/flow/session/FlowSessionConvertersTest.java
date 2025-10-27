package com.aiadvent.backend.flow.session;

import static org.assertj.core.api.Assertions.assertThat;

import com.aiadvent.backend.flow.session.converter.FlowLaunchParametersConverter;
import com.aiadvent.backend.flow.session.converter.FlowOverridesConverter;
import com.aiadvent.backend.flow.session.converter.FlowSharedContextConverter;
import com.aiadvent.backend.flow.session.model.FlowLaunchParameters;
import com.aiadvent.backend.flow.session.model.FlowOverrides;
import com.aiadvent.backend.flow.session.model.FlowSharedContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

class FlowSessionConvertersTest {

  private final FlowLaunchParametersConverter launchParametersConverter =
      new FlowLaunchParametersConverter();
  private final FlowSharedContextConverter sharedContextConverter =
      new FlowSharedContextConverter();
  private final FlowOverridesConverter overridesConverter = new FlowOverridesConverter();
  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void launchParametersRoundTrip() throws Exception {
    ObjectNode node = objectMapper.createObjectNode();
    node.put("customerId", "abc-123");
    node.put("priority", 3);

    FlowLaunchParameters parameters = FlowLaunchParameters.from(node);
    var dbValue = launchParametersConverter.convertToDatabaseColumn(parameters);
    FlowLaunchParameters restored = launchParametersConverter.convertToEntityAttribute(dbValue);

    assertThat(restored.isEmpty()).isFalse();
    assertThat(restored.asJson()).isEqualTo(node);
  }

  @Test
  void sharedContextPreservesCanonicalPayload() throws Exception {
    ObjectNode canonical = objectMapper.createObjectNode();
    canonical.putObject("initial").put("ticketId", "T-42");
    canonical.put("version", 2);
    canonical.putObject("steps");

    FlowSharedContext context = FlowSharedContext.from(canonical);
    var dbValue = sharedContextConverter.convertToDatabaseColumn(context);
    FlowSharedContext restored = sharedContextConverter.convertToEntityAttribute(dbValue);

    assertThat(restored.isEmpty()).isFalse();
    assertThat(restored.asJson()).isEqualTo(canonical);
  }

  @Test
  void overridesDefaultToEmpty() {
    var dbValue = overridesConverter.convertToDatabaseColumn(null);
    FlowOverrides restored = overridesConverter.convertToEntityAttribute(dbValue);

    assertThat(restored).isNotNull();
    assertThat(restored.isEmpty()).isTrue();
  }
}
