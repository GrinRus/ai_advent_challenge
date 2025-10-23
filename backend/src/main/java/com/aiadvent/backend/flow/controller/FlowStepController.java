package com.aiadvent.backend.flow.controller;

import com.aiadvent.backend.flow.api.FlowStepDetailResponse;
import com.aiadvent.backend.flow.service.FlowQueryService;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/flows")
public class FlowStepController {

  private final FlowQueryService flowQueryService;

  public FlowStepController(FlowQueryService flowQueryService) {
    this.flowQueryService = flowQueryService;
  }

  @GetMapping("/{sessionId}/steps/{stepId}")
  public FlowStepDetailResponse getStepDetails(
      @PathVariable UUID sessionId, @PathVariable String stepId) {
    return flowQueryService.fetchStepDetails(sessionId, stepId);
  }
}
