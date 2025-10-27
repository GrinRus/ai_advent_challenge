package com.aiadvent.backend.flow.controller;

import com.aiadvent.backend.flow.api.FlowStartRequest;
import com.aiadvent.backend.flow.api.FlowStartResponse;
import com.aiadvent.backend.flow.service.AgentOrchestratorService;
import com.aiadvent.backend.flow.session.model.FlowOverrides;
import com.aiadvent.backend.flow.session.model.FlowLaunchParameters;
import com.aiadvent.backend.flow.session.model.FlowSharedContext;
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

  public FlowLaunchController(AgentOrchestratorService agentOrchestratorService) {
    this.agentOrchestratorService = agentOrchestratorService;
  }

  @PostMapping("/{flowId}/start")
  public FlowStartResponse startFlow(
      @PathVariable UUID flowId, @RequestBody(required = false) FlowStartRequest request) {
    FlowLaunchParameters parameters =
        request != null && request.parameters() != null
            ? request.parameters()
            : FlowLaunchParameters.empty();
    FlowSharedContext context =
        request != null && request.sharedContext() != null
            ? request.sharedContext()
            : FlowSharedContext.empty();
    FlowOverrides overrides =
        request != null && request.overrides() != null
            ? request.overrides()
            : FlowOverrides.empty();
    UUID chatSessionId = request != null ? request.chatSessionId() : null;

    var session =
        agentOrchestratorService.start(flowId, parameters, context, overrides, chatSessionId);

    FlowLaunchParameters responseParameters =
        session.getLaunchParameters() != null && !session.getLaunchParameters().isEmpty()
            ? session.getLaunchParameters()
            : null;
    FlowSharedContext responseContext =
        session.getSharedContext() != null && !session.getSharedContext().isEmpty()
            ? session.getSharedContext()
            : null;
    FlowOverrides responseOverrides =
        session.getLaunchOverrides() != null && !session.getLaunchOverrides().isEmpty()
            ? session.getLaunchOverrides()
            : null;

    return new FlowStartResponse(
        session.getId(),
        session.getStatus(),
        session.getStartedAt(),
        responseParameters,
        responseContext,
        responseOverrides,
        session.getChatSessionId());
  }
}
