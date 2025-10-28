package com.aiadvent.backend.flow.validation;

public final class FlowBlueprintIssueCodes {

  public static final String BLUEPRINT_MISSING = "BLUEPRINT_MISSING";
  public static final String STEP_NULL = "STEP_NULL";
  public static final String STEP_ID_MISSING = "STEP_ID_MISSING";
  public static final String STEP_DUPLICATE = "STEP_DUPLICATE";
  public static final String AGENT_VERSION_INVALID = "AGENT_VERSION_INVALID";
  public static final String AGENT_VERSION_FORMAT_INVALID = "AGENT_VERSION_FORMAT_INVALID";
  public static final String INTERACTION_TYPE_UNSUPPORTED = "INTERACTION_TYPE_UNSUPPORTED";
  public static final String INTERACTION_SCHEMA_INVALID = "INTERACTION_SCHEMA_INVALID";
  public static final String INTERACTION_DUE_INVALID = "INTERACTION_DUE_INVALID";
  public static final String MEMORY_READS_FORMAT_INVALID = "MEMORY_READS_FORMAT_INVALID";
  public static final String MEMORY_WRITES_FORMAT_INVALID = "MEMORY_WRITES_FORMAT_INVALID";
  public static final String MEMORY_CHANNEL_INVALID = "MEMORY_CHANNEL_INVALID";
  public static final String MEMORY_CHANNEL_CONFLICT = "MEMORY_CHANNEL_CONFLICT";
  public static final String TRANSITION_TARGET_INVALID = "TRANSITION_TARGET_INVALID";

  private FlowBlueprintIssueCodes() {
    throw new AssertionError("Utility class");
  }
}
