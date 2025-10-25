package com.aiadvent.backend.chat.controller;

import com.aiadvent.backend.chat.api.AdminChatSummaryRequest;
import com.aiadvent.backend.chat.memory.ChatMemorySummarizerService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/chat/sessions")
public class ChatSummaryAdminController {

  private final ChatMemorySummarizerService summarizerService;

  public ChatSummaryAdminController(ChatMemorySummarizerService summarizerService) {
    this.summarizerService = summarizerService;
  }

  @PostMapping("/{sessionId}/summary/rebuild")
  public ResponseEntity<Void> rebuild(
      @PathVariable UUID sessionId, @RequestBody @Valid AdminChatSummaryRequest request) {
    summarizerService.forceSummarize(sessionId, request.providerId(), request.modelId());
    return ResponseEntity.accepted().build();
  }
}
