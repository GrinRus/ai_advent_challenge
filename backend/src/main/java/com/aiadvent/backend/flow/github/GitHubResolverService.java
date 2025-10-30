package com.aiadvent.backend.flow.github;

import com.aiadvent.backend.flow.service.AgentInvocationResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class GitHubResolverService {

  private final ObjectMapper objectMapper;

  public GitHubResolverService(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  public boolean supportsStep(String stepId) {
    return GitHubFlowConstants.STEP_RESOLVE_TARGET.equals(stepId);
  }

  public boolean deferInteractionCreation(String stepId) {
    return supportsStep(stepId);
  }

  public GitHubResolverPayload parsePayload(String content) {
    return GitHubResolverPayload.parse(objectMapper, content);
  }

  public AgentInvocationResult withStructuredContent(
      AgentInvocationResult original, GitHubResolverPayload payload) {
    JsonNode structured = toStructuredNode(payload);
    return new AgentInvocationResult(
        original.content(),
        original.usageCost(),
        original.memoryUpdates(),
        original.providerSelection(),
        original.appliedOverrides(),
        original.systemPrompt(),
        original.memorySnapshots(),
        original.userMessage(),
        original.selectedToolCodes(),
        structured);
  }

  public ObjectNode toStructuredNode(GitHubResolverPayload payload) {
    ObjectNode node = objectMapper.createObjectNode();
    node.put("status", payload.status().name());
    if (payload.target() != null) {
      node.set("githubTarget", payload.target().toJson(objectMapper));
    }
    if (StringUtils.hasText(payload.clarificationPrompt())) {
      node.put("clarificationPrompt", payload.clarificationPrompt());
    }
    if (StringUtils.hasText(payload.clarificationReason())) {
      node.put("clarificationReason", payload.clarificationReason());
    }
    List<String> missing = payload.missingFields();
    if (missing != null && !missing.isEmpty()) {
      ArrayNode missingNode = node.putArray("missingFields");
      missing.forEach(missingNode::add);
    }
    if (payload.raw() != null && !payload.raw().isNull()) {
      node.set("raw", payload.raw());
    }
    return node;
  }
}

