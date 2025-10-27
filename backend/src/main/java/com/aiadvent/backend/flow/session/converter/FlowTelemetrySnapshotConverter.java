package com.aiadvent.backend.flow.session.converter;

import com.aiadvent.backend.flow.session.model.FlowTelemetrySnapshot;
import com.aiadvent.backend.shared.json.AbstractJsonPayloadAttributeConverter;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Converter;

@Converter(autoApply = false)
public class FlowTelemetrySnapshotConverter
    extends AbstractJsonPayloadAttributeConverter<FlowTelemetrySnapshot> {

  @Override
  protected FlowTelemetrySnapshot emptyValue() {
    return FlowTelemetrySnapshot.empty();
  }

  @Override
  protected FlowTelemetrySnapshot createInstance(JsonNode value) {
    return FlowTelemetrySnapshot.from(value);
  }
}
