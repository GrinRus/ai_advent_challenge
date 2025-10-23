package com.aiadvent.backend.flow.controller;

import com.aiadvent.backend.flow.service.FlowStatusService;
import com.aiadvent.backend.flow.service.FlowStatusService.FlowStatusResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class FlowStatusController {

  private final FlowStatusService flowStatusService;

  public FlowStatusController(FlowStatusService flowStatusService) {
    this.flowStatusService = flowStatusService;
  }

  @GetMapping("/api/flows/{sessionId}")
  public ResponseEntity<FlowStatusResponse> pollStatus(
      @PathVariable UUID sessionId,
      @RequestParam(name = "sinceEventId", required = false) Long sinceEventId,
      @RequestParam(name = "stateVersion", required = false) Long stateVersion,
      @RequestParam(name = "timeout", required = false) Long timeoutMillis) {
    Duration timeout =
        timeoutMillis != null && timeoutMillis > 0
            ? Duration.ofMillis(timeoutMillis)
            : Duration.ofSeconds(25);

    Optional<FlowStatusResponse> response =
        flowStatusService.pollSession(sessionId, sinceEventId, stateVersion, timeout);

    return response.map(ResponseEntity::ok).orElse(ResponseEntity.noContent().build());
  }

  @GetMapping("/api/flows/{sessionId}/snapshot")
  public FlowStatusResponse snapshot(@PathVariable UUID sessionId) {
    return flowStatusService.currentSnapshot(sessionId);
  }
}
