package com.aiadvent.backend.flow.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.flow.api")
public class FlowApiProperties {

  /**
   * When enabled, flow-related REST endpoints return and accept typed payloads (`FlowBlueprint`,
   * typed step validation responses, richer reference data) instead of legacy `JsonNode`
   * structures.
   */
  private boolean v2Enabled = false;

  public boolean isV2Enabled() {
    return v2Enabled;
  }

  public void setV2Enabled(boolean v2Enabled) {
    this.v2Enabled = v2Enabled;
  }
}
