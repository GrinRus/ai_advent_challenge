package com.aiadvent.backend.flow.telemetry;

import com.aiadvent.backend.chat.provider.model.ChatRequestOverrides;
import com.aiadvent.backend.chat.provider.model.UsageCostEstimate;
import com.aiadvent.backend.flow.config.FlowStepConfig;
import com.aiadvent.backend.flow.domain.AgentVersion;
import com.aiadvent.backend.flow.domain.FlowMemoryVersion;
import com.aiadvent.backend.flow.domain.FlowSession;
import com.aiadvent.backend.flow.domain.FlowStepExecution;
import com.aiadvent.backend.flow.session.model.FlowLaunchParameters;
import com.aiadvent.backend.flow.session.model.FlowOverrides;
import com.aiadvent.backend.flow.session.model.FlowSharedContext;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

public final class FlowTraceFormatter {

  private FlowTraceFormatter() {}

  public static String stepDispatch(
      FlowSession session,
      FlowStepExecution execution,
      AgentVersion agentVersion,
      FlowStepConfig stepConfig,
      FlowOverrides sessionOverrides) {
    Map<String, Object> parts = baseSessionDetails(session);
    parts.put("stepId", execution.getStepId());
    parts.put("attempt", execution.getAttempt());
    if (execution.getStepName() != null && !execution.getStepName().isBlank()) {
      parts.put("stepName", execution.getStepName());
    }
    appendAgent(parts, agentVersion);
    appendOverrides(parts, "step", stepConfig != null ? stepConfig.overrides() : null);
    appendOverrides(parts, "session", sessionOverrides);
    appendLaunchParameters(parts, session.getLaunchParameters());
    appendSharedContext(parts, session.getSharedContext());
    if (stepConfig != null) {
      parts.put("memoryReads", stepConfig.memoryReads().size());
      parts.put("memoryWrites", stepConfig.memoryWrites().size());
    }
    return format("step-dispatch", parts);
  }

  public static String stepCompletion(
      FlowSession session,
      FlowStepExecution execution,
      AgentVersion agentVersion,
      UsageCostEstimate usageCost,
      List<FlowMemoryVersion> memoryUpdates,
      JsonNode stepOutput,
      Duration duration) {
    Map<String, Object> parts = baseSessionDetails(session);
    parts.put("stepId", execution.getStepId());
    parts.put("attempt", execution.getAttempt());
    appendAgent(parts, agentVersion);
    if (usageCost != null) {
      parts.put("promptTokens", usageCost.promptTokens());
      parts.put("completionTokens", usageCost.completionTokens());
      parts.put("totalTokens", usageCost.totalTokens());
      if (usageCost.totalCost() != null) {
        parts.put("costTotalUsd", usageCost.totalCost());
      }
    }
    if (duration != null) {
      parts.put("durationMs", duration.toMillis());
    }
    if (memoryUpdates != null && !memoryUpdates.isEmpty()) {
      parts.put(
          "memoryUpdates",
          memoryUpdates.stream().map(FlowMemoryVersion::getChannel).distinct().collect(Collectors.toList()));
    }
    parts.put("outputSummary", summarizeJson(stepOutput));
    appendSharedContext(parts, session.getSharedContext());
    return format("step-completed", parts);
  }

  public static String stepFailure(
      FlowSession session,
      FlowStepExecution execution,
      AgentVersion agentVersion,
      Throwable throwable,
      Duration duration) {
    Map<String, Object> parts = baseSessionDetails(session);
    parts.put("stepId", execution.getStepId());
    parts.put("attempt", execution.getAttempt());
    appendAgent(parts, agentVersion);
    if (duration != null) {
      parts.put("durationMs", duration.toMillis());
    }
    if (throwable != null) {
      parts.put("errorType", throwable.getClass().getSimpleName());
      parts.put("errorMessage", throwable.getMessage());
    }
    if (execution.getErrorCode() != null) {
      parts.put("errorCode", execution.getErrorCode());
    }
    appendSharedContext(parts, session.getSharedContext());
    return format("step-failed", parts);
  }

  private static Map<String, Object> baseSessionDetails(FlowSession session) {
    Map<String, Object> parts = new LinkedHashMap<>();
    parts.put("sessionId", session.getId());
    parts.put("status", session.getStatus());
    parts.put("stateVersion", session.getStateVersion());
    parts.put("memoryVersion", session.getCurrentMemoryVersion());
    parts.put("definitionId", session.getFlowDefinition().getId());
    parts.put("definitionVersion", session.getFlowDefinitionVersion());
    return parts;
  }

  private static void appendAgent(Map<String, Object> parts, AgentVersion agentVersion) {
    if (agentVersion == null) {
      return;
    }
    parts.put("agentVersion", agentVersion.getVersion());
    parts.put("agentVersionId", agentVersion.getId());
    parts.put("providerId", agentVersion.getProviderId());
    if (agentVersion.getProviderType() != null) {
      parts.put("providerType", agentVersion.getProviderType().name());
    }
    parts.put("modelId", agentVersion.getModelId());
  }

  private static void appendLaunchParameters(
      Map<String, Object> parts, FlowLaunchParameters launchParameters) {
    if (launchParameters == null || launchParameters.isEmpty()) {
      return;
    }
    List<String> keys = launchParameters.fieldNames(5);
    if (!keys.isEmpty()) {
      parts.put("launchParams", keys);
    }
  }

  private static void appendSharedContext(Map<String, Object> parts, FlowSharedContext sharedContext) {
    if (sharedContext == null || sharedContext.isEmpty()) {
      return;
    }
    parts.put("sharedVersion", sharedContext.version());
    String lastStep = sharedContext.lastStepId();
    if (lastStep != null) {
      parts.put("sharedLastStep", lastStep);
    }
    List<String> keys = sharedContext.stepKeys(5);
    if (!keys.isEmpty()) {
      parts.put("sharedSteps", keys);
    }
    parts.put("sharedHasLastOutput", sharedContext.hasLastOutput());
  }

  private static void appendOverrides(
      Map<String, Object> parts, String prefix, ChatRequestOverrides overrides) {
    if (overrides == null) {
      return;
    }
    if (overrides.temperature() != null) {
      parts.put(prefix + "Temperature", overrides.temperature());
    }
    if (overrides.topP() != null) {
      parts.put(prefix + "TopP", overrides.topP());
    }
    if (overrides.maxTokens() != null) {
      parts.put(prefix + "MaxTokens", overrides.maxTokens());
    }
  }

  private static void appendOverrides(
      Map<String, Object> parts, String prefix, FlowOverrides overrides) {
    if (overrides == null || overrides.isEmpty()) {
      return;
    }
    if (overrides.temperature() != null) {
      parts.put(prefix + "Temperature", overrides.temperature());
    }
    if (overrides.topP() != null) {
      parts.put(prefix + "TopP", overrides.topP());
    }
    if (overrides.maxTokens() != null) {
      parts.put(prefix + "MaxTokens", overrides.maxTokens());
    }
  }

  private static String summarizeJson(JsonNode node) {
    if (node == null || node.isMissingNode() || node.isNull()) {
      return "none";
    }
    if (node.isValueNode()) {
      return node.asText();
    }
    if (node.isArray()) {
      return "array(size=" + node.size() + ")";
    }
    if (node.isObject()) {
      List<String> keys = new ArrayList<>();
      node.fieldNames()
          .forEachRemaining(
              name -> {
                if (keys.size() < 5) {
                  keys.add(name);
                }
              });
      if (node.size() > keys.size()) {
        keys.add("…");
      }
      return "object(" + String.join(",", keys) + ")";
    }
    return node.getNodeType().name().toLowerCase();
  }

  private static String format(String event, Map<String, Object> parts) {
    String body =
        parts.entrySet().stream()
            .filter(entry -> entry.getValue() != null && !Objects.toString(entry.getValue()).isBlank())
            .map(entry -> entry.getKey() + "=" + entry.getValue())
            .collect(Collectors.joining(", "));
    return event + " [" + body + "]";
  }
}
