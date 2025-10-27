package com.aiadvent.backend.flow.api;

import com.aiadvent.backend.flow.agent.options.AgentInvocationOptions;
import java.util.List;

public record AgentConstructorPoliciesResponse(
    List<RetryPolicyPreset> retryPolicies,
    List<MemoryPolicyPreset> memoryPolicies,
    List<AdvisorPolicyPreset> advisorPolicies) {

  public record RetryPolicyPreset(String id, String label, AgentInvocationOptions.RetryPolicy policy) {}

  public record MemoryPolicyPreset(
      String id, String label, AgentInvocationOptions.MemoryPolicy policy) {}

  public record AdvisorPolicyPreset(
      String id, String label, AgentInvocationOptions.AdvisorSettings settings) {}
}

