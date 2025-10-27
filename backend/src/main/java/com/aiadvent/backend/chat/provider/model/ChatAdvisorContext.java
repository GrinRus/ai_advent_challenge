package com.aiadvent.backend.chat.provider.model;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.util.StringUtils;

/**
 * Typed container for advisor parameters that are passed to the underlying Spring AI prompt
 * builder. Eliminates ad-hoc {@code Map<String, Object>} usage and validates supported fields.
 */
public record ChatAdvisorContext(
    UUID flowSessionId,
    UUID flowStepExecutionId,
    String flowStepId,
    Integer flowStepAttempt) {

  public ChatAdvisorContext {
    if (flowSessionId == null) {
      throw new IllegalArgumentException("flowSessionId must not be null");
    }
    if (flowStepAttempt != null && flowStepAttempt < 1) {
      throw new IllegalArgumentException("flowStepAttempt must be >= 1 when specified");
    }
  }

  public static Builder builder() {
    return new Builder();
  }

  public Map<String, Object> asParameters() {
    Map<String, Object> params = new LinkedHashMap<>();
    params.put("flowSessionId", flowSessionId.toString());
    if (flowStepExecutionId != null) {
      params.put("flowStepExecutionId", flowStepExecutionId.toString());
    }
    if (StringUtils.hasText(flowStepId)) {
      params.put("flowStepId", flowStepId);
    }
    if (flowStepAttempt != null) {
      params.put("flowStepAttempt", flowStepAttempt);
    }
    return Map.copyOf(params);
  }

  public static final class Builder {
    private UUID flowSessionId;
    private UUID flowStepExecutionId;
    private String flowStepId;
    private Integer flowStepAttempt;

    private Builder() {}

    public Builder flowSessionId(UUID flowSessionId) {
      this.flowSessionId = flowSessionId;
      return this;
    }

    public Builder flowStepExecutionId(UUID flowStepExecutionId) {
      this.flowStepExecutionId = flowStepExecutionId;
      return this;
    }

    public Builder flowStepId(String flowStepId) {
      this.flowStepId = flowStepId;
      return this;
    }

    public Builder flowStepAttempt(Integer flowStepAttempt) {
      this.flowStepAttempt = flowStepAttempt;
      return this;
    }

    public ChatAdvisorContext build() {
      return new ChatAdvisorContext(flowSessionId, flowStepExecutionId, flowStepId, flowStepAttempt);
    }
  }
}
