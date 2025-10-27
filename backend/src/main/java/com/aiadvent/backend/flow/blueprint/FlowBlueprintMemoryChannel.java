package com.aiadvent.backend.flow.blueprint;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record FlowBlueprintMemoryChannel(String id, Integer retentionVersions, Integer retentionDays) {

  public FlowBlueprintMemoryChannel {
    if (id == null || id.isBlank()) {
      throw new IllegalArgumentException("Memory channel id must not be blank");
    }
    retentionVersions = retentionVersions != null && retentionVersions > 0 ? retentionVersions : null;
    retentionDays = retentionDays != null && retentionDays > 0 ? retentionDays : null;
  }
}

