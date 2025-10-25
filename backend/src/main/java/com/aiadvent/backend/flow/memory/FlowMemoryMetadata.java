package com.aiadvent.backend.flow.memory;

import java.util.UUID;

public class FlowMemoryMetadata {

  private final FlowMemorySourceType sourceType;
  private final String stepId;
  private final Integer stepAttempt;
  private final UUID agentVersionId;
  private final UUID createdByStepId;

  private FlowMemoryMetadata(Builder builder) {
    this.sourceType = builder.sourceType;
    this.stepId = builder.stepId;
    this.stepAttempt = builder.stepAttempt;
    this.agentVersionId = builder.agentVersionId;
    this.createdByStepId = builder.createdByStepId;
  }

  public static Builder builder() {
    return new Builder();
  }

  public static FlowMemoryMetadata empty() {
    return builder().build();
  }

  public FlowMemorySourceType sourceType() {
    return sourceType;
  }

  public String stepId() {
    return stepId;
  }

  public Integer stepAttempt() {
    return stepAttempt;
  }

  public UUID agentVersionId() {
    return agentVersionId;
  }

  public UUID createdByStepId() {
    return createdByStepId;
  }

  public static final class Builder {
    private FlowMemorySourceType sourceType;
    private String stepId;
    private Integer stepAttempt;
    private UUID agentVersionId;
    private UUID createdByStepId;

    private Builder() {}

    public Builder sourceType(FlowMemorySourceType sourceType) {
      this.sourceType = sourceType;
      return this;
    }

    public Builder stepId(String stepId) {
      this.stepId = stepId;
      return this;
    }

    public Builder stepAttempt(Integer stepAttempt) {
      this.stepAttempt = stepAttempt;
      return this;
    }

    public Builder agentVersionId(UUID agentVersionId) {
      this.agentVersionId = agentVersionId;
      return this;
    }

    public Builder createdByStepId(UUID createdByStepId) {
      this.createdByStepId = createdByStepId;
      return this;
    }

    public FlowMemoryMetadata build() {
      return new FlowMemoryMetadata(this);
    }
  }
}
