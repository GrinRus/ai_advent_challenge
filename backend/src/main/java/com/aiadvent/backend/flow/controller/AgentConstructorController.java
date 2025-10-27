package com.aiadvent.backend.flow.controller;

import com.aiadvent.backend.flow.api.AgentConstructorPoliciesResponse;
import com.aiadvent.backend.flow.api.AgentConstructorPreviewRequest;
import com.aiadvent.backend.flow.api.AgentConstructorPreviewResponse;
import com.aiadvent.backend.flow.api.AgentConstructorProvidersResponse;
import com.aiadvent.backend.flow.api.AgentConstructorToolsResponse;
import com.aiadvent.backend.flow.api.AgentConstructorValidateRequest;
import com.aiadvent.backend.flow.api.AgentConstructorValidateResponse;
import com.aiadvent.backend.flow.service.AgentConstructorService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/agents/constructor")
public class AgentConstructorController {

  private final AgentConstructorService agentConstructorService;

  public AgentConstructorController(AgentConstructorService agentConstructorService) {
    this.agentConstructorService = agentConstructorService;
  }

  @GetMapping("/providers")
  public AgentConstructorProvidersResponse providers() {
    return agentConstructorService.listProviders();
  }

  @GetMapping("/tools")
  public AgentConstructorToolsResponse tools() {
    return agentConstructorService.listTools();
  }

  @GetMapping("/policies")
  public AgentConstructorPoliciesResponse policies() {
    return agentConstructorService.listPolicies();
  }

  @PostMapping("/validate")
  public AgentConstructorValidateResponse validate(
      @RequestBody AgentConstructorValidateRequest request) {
    return agentConstructorService.validate(request);
  }

  @PostMapping("/preview")
  @ResponseStatus(HttpStatus.OK)
  public AgentConstructorPreviewResponse preview(
      @RequestBody AgentConstructorPreviewRequest request) {
    return agentConstructorService.preview(request);
  }
}

