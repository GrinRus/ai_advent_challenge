package com.aiadvent.backend.chat.api;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(
    description = "Processing status for a structured sync request.",
    enumAsRef = true)
public enum StructuredSyncStatus {
  SUCCESS("success"),
  PARTIAL("partial"),
  FAILURE("failure");

  private final String value;

  StructuredSyncStatus(String value) {
    this.value = value;
  }

  @JsonValue
  public String value() {
    return value;
  }

  @JsonCreator
  public static StructuredSyncStatus fromValue(String raw) {
    if (raw == null) {
      return null;
    }
    for (StructuredSyncStatus status : values()) {
      if (status.value.equalsIgnoreCase(raw)) {
        return status;
      }
    }
    throw new IllegalArgumentException("Unknown structured sync status: " + raw);
  }
}
