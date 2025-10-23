package com.aiadvent.backend.flow.job;

import java.util.UUID;

public record FlowJobPayload(UUID flowSessionId, UUID stepExecutionId, String stepId, int attempt) {

  public FlowJobPayload {
    if (flowSessionId == null) {
      throw new IllegalArgumentException("flowSessionId must not be null");
    }
    if (stepExecutionId == null) {
      throw new IllegalArgumentException("stepExecutionId must not be null");
    }
    if (stepId == null || stepId.isBlank()) {
      throw new IllegalArgumentException("stepId must not be blank");
    }
    attempt = attempt <= 0 ? 1 : attempt;
  }
}
