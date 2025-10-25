package com.aiadvent.backend.flow.controller;

import com.aiadvent.backend.flow.api.AdminFlowSummaryRequest;
import com.aiadvent.backend.flow.memory.FlowMemorySummarizerService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/flows/sessions")
public class FlowSummaryAdminController {

  private final FlowMemorySummarizerService flowMemorySummarizerService;

  public FlowSummaryAdminController(FlowMemorySummarizerService flowMemorySummarizerService) {
    this.flowMemorySummarizerService = flowMemorySummarizerService;
  }

  @PostMapping("/{sessionId}/summary/rebuild")
  public ResponseEntity<Void> rebuild(
      @PathVariable UUID sessionId, @Valid @RequestBody AdminFlowSummaryRequest request) {
    flowMemorySummarizerService.forceSummarize(
        sessionId, request.providerId(), request.modelId(), request.normalizedChannels());
    return ResponseEntity.accepted().build();
  }
}
