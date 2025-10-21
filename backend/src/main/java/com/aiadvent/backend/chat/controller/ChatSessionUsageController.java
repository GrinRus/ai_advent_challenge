package com.aiadvent.backend.chat.controller;

import com.aiadvent.backend.chat.api.SessionUsageResponse;
import com.aiadvent.backend.chat.service.SessionUsageService;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/llm/sessions")
public class ChatSessionUsageController {

  private final SessionUsageService sessionUsageService;

  public ChatSessionUsageController(SessionUsageService sessionUsageService) {
    this.sessionUsageService = sessionUsageService;
  }

  @GetMapping("/{sessionId}/usage")
  public SessionUsageResponse usage(@PathVariable UUID sessionId) {
    return sessionUsageService.summarize(sessionId);
  }
}
