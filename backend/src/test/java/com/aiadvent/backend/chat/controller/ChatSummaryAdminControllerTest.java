package com.aiadvent.backend.chat.controller;

import static org.mockito.Mockito.verify;

import com.aiadvent.backend.chat.api.AdminChatSummaryRequest;
import com.aiadvent.backend.chat.memory.ChatMemorySummarizerService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;

class ChatSummaryAdminControllerTest {

  private final ChatMemorySummarizerService summarizerService =
      org.mockito.Mockito.mock(ChatMemorySummarizerService.class);

  private final ChatSummaryAdminController controller =
      new ChatSummaryAdminController(summarizerService);

  @Test
  void rebuildInvokesSummarizer() {
    UUID sessionId = UUID.randomUUID();
    AdminChatSummaryRequest request = new AdminChatSummaryRequest("openai", "gpt-4o-mini");

    ResponseEntity<Void> response = controller.rebuild(sessionId, request);

    ArgumentCaptor<UUID> sessionCaptor = ArgumentCaptor.forClass(UUID.class);
    verify(summarizerService)
        .forceSummarize(sessionCaptor.capture(), org.mockito.Mockito.eq("openai"), org.mockito.Mockito.eq("gpt-4o-mini"));
    assert response.getStatusCode().is2xxSuccessful();
  }
}
