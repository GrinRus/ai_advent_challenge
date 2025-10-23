package com.aiadvent.backend.flow.controller;

import com.aiadvent.backend.flow.api.FlowControlRequest;
import com.aiadvent.backend.flow.domain.FlowSession;
import com.aiadvent.backend.flow.service.FlowControlService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/flows")
public class FlowControlController {

  private final FlowControlService flowControlService;

  public FlowControlController(FlowControlService flowControlService) {
    this.flowControlService = flowControlService;
  }

  @PostMapping("/{sessionId}/control")
  public ResponseEntity<?> control(
      @PathVariable UUID sessionId, @RequestBody FlowControlRequest request) {
    if (request == null || request.command() == null) {
      return ResponseEntity.badRequest().body("Command is required");
    }

    String command = request.command().toLowerCase();
    FlowSession session;

    switch (command) {
      case "pause" -> session = flowControlService.pause(sessionId, "manual");
      case "resume" -> session = flowControlService.resume(sessionId);
      case "cancel" -> session = flowControlService.cancel(sessionId, "manual");
      case "retrystep" -> {
        if (request.stepExecutionId() == null) {
          return ResponseEntity.badRequest().body("stepExecutionId is required for retryStep");
        }
        flowControlService.retryStep(sessionId, request.stepExecutionId());
        session = flowControlService.resume(sessionId);
      }
      default -> {
        return ResponseEntity.badRequest().body("Unsupported command: " + request.command());
      }
    }

    return ResponseEntity.ok(session.getStatus());
  }
}
