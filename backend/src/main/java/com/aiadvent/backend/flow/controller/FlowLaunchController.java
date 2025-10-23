package com.aiadvent.backend.flow.controller;

import com.aiadvent.backend.flow.api.FlowStartRequest;
import com.aiadvent.backend.flow.api.FlowStartResponse;
import com.aiadvent.backend.flow.service.AgentOrchestratorService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/flows")
public class FlowLaunchController {

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
    JsonNode parameters = request != null ? request.parameters() : objectMapper.nullNode();
    JsonNode context = request != null ? request.sharedContext() : objectMapper.nullNode();
    var session = agentOrchestratorService.start(flowId, parameters, context);
    return new FlowStartResponse(session.getId(), session.getStatus(), session.getStartedAt());
  }
}
