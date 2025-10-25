package com.aiadvent.backend.flow.memory;

/**
 * Canonical flow memory channel identifiers shared between orchestration, memory readers and
 * summarisation helpers.
 */
public final class FlowMemoryChannels {

  public static final String CONVERSATION = "conversation";
  public static final String SHARED = "shared";

  private FlowMemoryChannels() {
    throw new AssertionError("Utility class");
  }
}
