package com.aiadvent.backend.flow.controller;

import com.aiadvent.backend.chat.provider.model.ChatRequestOverrides;
import com.aiadvent.backend.flow.api.FlowStartRequest;
import com.aiadvent.backend.flow.api.FlowStartResponse;
import com.aiadvent.backend.flow.service.AgentOrchestratorService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/flows")
public class FlowLaunchController {

  private static final Logger log = LoggerFactory.getLogger(FlowLaunchController.class);

  private final AgentOrchestratorService agentOrchestratorService;
  private final ObjectMapper objectMapper;

  public FlowLaunchController(
      AgentOrchestratorService agentOrchestratorService, ObjectMapper objectMapper) {
    this.agentOrchestratorService = agentOrchestratorService;
    this.objectMapper = objectMapper;
  }

  @PostMapping("/{flowId}/start")
  public FlowStartResponse startFlow(
      @PathVariable UUID flowId, @RequestBody(required = false) FlowStartRequest request) {
    JsonNode parameters = sanitizeNode(request != null ? request.parameters() : null);
    JsonNode context = sanitizeNode(request != null ? request.sharedContext() : null);
    ChatRequestOverrides overrides = request != null ? request.overrides() : null;
    UUID chatSessionId = request != null ? request.chatSessionId() : null;

    var session =
        agentOrchestratorService.start(flowId, parameters, context, overrides, chatSessionId);
    ChatRequestOverrides sessionOverrides = extractOverrides(session.getLaunchOverrides());

    return new FlowStartResponse(
        session.getId(),
        session.getStatus(),
        session.getStartedAt(),
        session.getLaunchParameters(),
        session.getSharedContext(),
        sessionOverrides,
        session.getChatSessionId());
  }

  private JsonNode sanitizeNode(JsonNode node) {
    return node != null ? node : objectMapper.nullNode();
  }

  private ChatRequestOverrides extractOverrides(JsonNode node) {
    if (node == null || node.isNull()) {
      return null;
    }
    try {
      return objectMapper.treeToValue(node, ChatRequestOverrides.class);
    } catch (Exception exception) {
      log.warn("Failed to deserialize launch overrides from session payload", exception);
      return null;
    }
  }
}
