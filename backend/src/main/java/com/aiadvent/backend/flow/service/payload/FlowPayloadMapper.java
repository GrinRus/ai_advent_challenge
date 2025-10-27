package com.aiadvent.backend.flow.service.payload;

import com.aiadvent.backend.chat.provider.model.UsageCostEstimate;
import com.aiadvent.backend.flow.agent.options.AgentInvocationOptions;
import com.aiadvent.backend.flow.domain.AgentVersion;
import com.aiadvent.backend.flow.domain.FlowSession;
import com.aiadvent.backend.flow.domain.FlowStepExecution;
import com.aiadvent.backend.flow.execution.model.FlowCostPayload;
import com.aiadvent.backend.flow.execution.model.FlowEventPayload;
import com.aiadvent.backend.flow.execution.model.FlowStepInputPayload;
import com.aiadvent.backend.flow.execution.model.FlowStepOutputPayload;
import com.aiadvent.backend.flow.execution.model.FlowUsagePayload;
import com.aiadvent.backend.flow.session.model.FlowLaunchParameters;
import com.aiadvent.backend.flow.session.model.FlowOverrides;
import com.aiadvent.backend.flow.session.model.FlowSharedContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class FlowPayloadMapper {

  private final ObjectMapper objectMapper;

  public FlowPayloadMapper(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  public FlowSharedContext initializeSharedContext(JsonNode sharedContext) {
    if (sharedContext != null && !sharedContext.isNull() && sharedContext.isObject()) {
      boolean looksLikeCanonical =
          sharedContext.has("initial")
              || sharedContext.has("current")
              || sharedContext.has("steps")
              || sharedContext.has("version")
              || sharedContext.has("lastOutput");
      if (looksLikeCanonical) {
        return FlowSharedContext.from(sharedContext);
      }
    }
    ObjectNode context = objectMapper.createObjectNode();
    context.set("initial", cloneNode(sharedContext));
    context.set("current", cloneNode(sharedContext));
    context.set("steps", objectMapper.createObjectNode());
    context.set("lastOutput", objectMapper.nullNode());
    context.putNull("lastStepId");
    context.put("version", 0);
    return FlowSharedContext.from(context);
  }

  public FlowStepInputPayload buildStepInputPayload(FlowSession session) {
    ObjectNode input = objectMapper.createObjectNode();
    FlowLaunchParameters launchParameters = session.getLaunchParameters();
    if (!launchParameters.isEmpty()) {
      input.set("launchParameters", launchParameters.asJson());
    }

    FlowSharedContext shared = session.getSharedContext();
    if (!shared.isEmpty()) {
      ObjectNode sharedNode = shared.asObjectNode(objectMapper);
      input.set("sharedContext", sharedNode.deepCopy());
      JsonNode initial = sharedNode.get("initial");
      if (initial != null) {
        input.set("initialContext", cloneNode(initial));
      }
      JsonNode lastOutput = sharedNode.get("lastOutput");
      if (lastOutput != null && !lastOutput.isNull()) {
        input.set("lastOutput", cloneNode(lastOutput));
      }
      JsonNode current = sharedNode.get("current");
      if (current != null && !current.isNull()) {
        input.set("currentContext", cloneNode(current));
      }
    }
    return FlowStepInputPayload.from(input);
  }

  public FlowSharedContext applyStepOutput(
      FlowSession session, FlowStepExecution stepExecution, JsonNode stepOutput) {
    ObjectNode context = session.getSharedContext().asObjectNode(objectMapper);

    ObjectNode stepsNode = context.with("steps");
    stepsNode.set(stepExecution.getStepId(), cloneNode(stepOutput));
    context.put("lastStepId", stepExecution.getStepId());
    context.set("lastOutput", cloneNode(stepOutput));
    context.set("current", cloneNode(stepOutput));
    context.put("version", context.path("version").asInt(0) + 1);
    return FlowSharedContext.from(context);
  }

  public FlowEventPayload eventPayload(
      FlowSession session, FlowStepExecution stepExecution, JsonNode payload) {
    return FlowEventPayload.from(
        enrichPayloadWithContext(session, attachStepMetadata(stepExecution, payload)));
  }

  public FlowEventPayload eventPayload(FlowSession session, JsonNode payload) {
    return FlowEventPayload.from(enrichPayloadWithContext(session, payload));
  }

  public FlowUsagePayload usagePayload(UsageCostEstimate usageCost) {
    if (usageCost == null) {
      return FlowUsagePayload.empty();
    }
    ObjectNode node = objectMapper.createObjectNode();
    if (usageCost.promptTokens() != null) {
      node.put("promptTokens", usageCost.promptTokens());
    }
    if (usageCost.completionTokens() != null) {
      node.put("completionTokens", usageCost.completionTokens());
    }
    if (usageCost.totalTokens() != null) {
      node.put("totalTokens", usageCost.totalTokens());
    }
    return FlowUsagePayload.from(node);
  }

  public FlowCostPayload costPayload(UsageCostEstimate usageCost) {
    if (usageCost == null) {
      return FlowCostPayload.empty();
    }
    ObjectNode node = objectMapper.createObjectNode();
    if (usageCost.inputCost() != null) {
      node.put("input", usageCost.inputCost().doubleValue());
    }
    if (usageCost.outputCost() != null) {
      node.put("output", usageCost.outputCost().doubleValue());
    }
    if (usageCost.totalCost() != null) {
      node.put("total", usageCost.totalCost().doubleValue());
    }
    if (usageCost.currency() != null) {
      node.put("currency", usageCost.currency());
    }
    return FlowCostPayload.from(node);
  }

  private JsonNode attachStepMetadata(FlowStepExecution stepExecution, JsonNode payload) {
    if (stepExecution == null) {
      return payload;
    }
    ObjectNode metadata = objectMapper.createObjectNode();
    if (stepExecution.getId() != null) {
      metadata.put("stepExecutionId", stepExecution.getId().toString());
    }
    metadata.put("stepId", stepExecution.getStepId());
    if (StringUtils.hasText(stepExecution.getStepName())) {
      metadata.put("stepName", stepExecution.getStepName());
    }
    metadata.put("attempt", stepExecution.getAttempt());
    if (stepExecution.getStatus() != null) {
      metadata.put("status", stepExecution.getStatus().name());
    }
    if (StringUtils.hasText(stepExecution.getPrompt())) {
      metadata.put("prompt", stepExecution.getPrompt());
    }
    AgentVersion agentVersion = stepExecution.getAgentVersion();
    if (agentVersion != null) {
      metadata.set("agentVersion", buildAgentVersionNode(agentVersion));
    }
    if (metadata.size() == 0) {
      return payload;
    }
    if (payload instanceof ObjectNode objectNode) {
      if (!objectNode.has("step")) {
        objectNode.set("step", metadata);
      }
      return objectNode;
    }
    if (payload == null || payload.isNull()) {
      return metadata;
    }
    ObjectNode wrapper = objectMapper.createObjectNode();
    wrapper.set("data", cloneNode(payload));
    wrapper.set("step", metadata);
    return wrapper;
  }

  private JsonNode enrichPayloadWithContext(FlowSession session, JsonNode payload) {
    ObjectNode contextNode = buildContextNode(session);
    if (contextNode == null || contextNode.size() == 0) {
      return payload;
    }
    if (payload instanceof ObjectNode objectNode) {
      objectNode.set("context", contextNode);
      return objectNode;
    }
    if (payload == null || payload.isNull()) {
      return contextNode;
    }
    ObjectNode wrapper = objectMapper.createObjectNode();
    wrapper.set("data", cloneNode(payload));
    wrapper.set("context", contextNode);
    return wrapper;
  }

  private ObjectNode buildContextNode(FlowSession session) {
    ObjectNode node = objectMapper.createObjectNode();
    FlowLaunchParameters launchParameters = session.getLaunchParameters();
    if (!launchParameters.isEmpty()) {
      node.set("launchParameters", launchParameters.asJson());
    }
    FlowOverrides overrides = session.getLaunchOverrides();
    if (!overrides.isEmpty()) {
      node.set("launchOverrides", overrides.asJson());
    }
    return node.size() > 0 ? node : null;
  }

  private ObjectNode buildAgentVersionNode(AgentVersion agentVersion) {
    ObjectNode agentNode = objectMapper.createObjectNode();
    if (agentVersion.getId() != null) {
      agentNode.put("id", agentVersion.getId().toString());
    }
    agentNode.put("version", agentVersion.getVersion());
    if (agentVersion.getProviderType() != null) {
      agentNode.put("providerType", agentVersion.getProviderType().name());
    }
    agentNode.put("providerId", agentVersion.getProviderId());
    agentNode.put("modelId", agentVersion.getModelId());
    if (StringUtils.hasText(agentVersion.getSystemPrompt())) {
      agentNode.put("systemPrompt", agentVersion.getSystemPrompt());
    }
    agentNode.put("syncOnly", agentVersion.isSyncOnly());
    if (agentVersion.getMaxTokens() != null) {
      agentNode.put("maxTokens", agentVersion.getMaxTokens());
    }
    AgentInvocationOptions invocationOptions = agentVersion.getInvocationOptions();
    if (invocationOptions != null && !AgentInvocationOptions.empty().equals(invocationOptions)) {
      agentNode.set("invocationOptions", objectMapper.valueToTree(invocationOptions));
    }
    return agentNode;
  }

  private JsonNode cloneNode(JsonNode node) {
    if (node == null || node.isNull()) {
      return objectMapper.nullNode();
    }
    return node.deepCopy();
  }
}
