package com.aiadvent.backend.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aiadvent.backend.chat.domain.ChatMessage;
import com.aiadvent.backend.chat.domain.ChatRole;
import com.aiadvent.backend.chat.domain.ChatSession;
import com.aiadvent.backend.chat.persistence.ChatMessageRepository;
import com.aiadvent.backend.chat.persistence.ChatSessionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class ChatServiceTest {

  @Mock private ChatSessionRepository chatSessionRepository;

  @Mock private ChatMessageRepository chatMessageRepository;

  private ChatService chatService;

  @BeforeEach
  void setUp() {
    chatService = new ChatService(chatSessionRepository, chatMessageRepository);
  }

  @Test
  void registerUserMessageCreatesNewSession() {
    ChatSession savedSession = new ChatSession();
    UUID sessionId = UUID.randomUUID();
    ReflectionTestUtils.setField(savedSession, "id", sessionId);

    when(chatSessionRepository.save(any(ChatSession.class))).thenReturn(savedSession);
    when(chatMessageRepository.findTopBySessionOrderBySequenceNumberDesc(savedSession))
        .thenReturn(Optional.empty());

    ChatMessage persistedMessage =
        new ChatMessage(savedSession, ChatRole.USER, "Привет", 1, "z.ai", "glm");
    when(chatMessageRepository.save(any(ChatMessage.class))).thenReturn(persistedMessage);

    ConversationContext context =
        chatService.registerUserMessage(null, "Привет", "z.ai", "glm");

    assertThat(context.sessionId()).isEqualTo(sessionId);
    assertThat(context.newSession()).isTrue();

    ArgumentCaptor<ChatMessage> messageCaptor = ArgumentCaptor.forClass(ChatMessage.class);
    verify(chatMessageRepository).save(messageCaptor.capture());
    ChatMessage value = messageCaptor.getValue();
    assertThat(value.getSequenceNumber()).isEqualTo(1);
    assertThat(value.getRole()).isEqualTo(ChatRole.USER);
    assertThat(value.getProvider()).isEqualTo("z.ai");
    assertThat(value.getModel()).isEqualTo("glm");
  }

  @Test
  void registerUserMessageFailsForUnknownSession() {
    UUID missingSession = UUID.randomUUID();
    when(chatSessionRepository.findById(missingSession)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> chatService.registerUserMessage(missingSession, "test", "z.ai", "glm"))
        .isInstanceOf(ResponseStatusException.class)
        .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void registerAssistantMessageSkipsEmptyContent() {
    chatService.registerAssistantMessage(UUID.randomUUID(), "  ", "z.ai", "glm");
    verify(chatSessionRepository, never()).findById(any());
  }

  @Test
  void registerAssistantMessageStoresResponse() {
    ChatSession session = new ChatSession();
    UUID sessionId = UUID.randomUUID();
    ReflectionTestUtils.setField(session, "id", sessionId);

    when(chatSessionRepository.findById(sessionId)).thenReturn(Optional.of(session));
    when(chatMessageRepository.findTopBySessionOrderBySequenceNumberDesc(session))
        .thenReturn(Optional.of(new ChatMessage(session, ChatRole.USER, "msg", 1, "z.ai", "glm")));
    when(chatMessageRepository.save(any(ChatMessage.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    chatService.registerAssistantMessage(sessionId, "Ответ бота", "z.ai", "glm");

    ArgumentCaptor<ChatMessage> messageCaptor = ArgumentCaptor.forClass(ChatMessage.class);
    verify(chatMessageRepository).save(messageCaptor.capture());
    ChatMessage saved = messageCaptor.getValue();
    assertThat(saved.getRole()).isEqualTo(ChatRole.ASSISTANT);
    assertThat(saved.getSequenceNumber()).isEqualTo(2);
    assertThat(saved.getContent()).isEqualTo("Ответ бота");
    assertThat(saved.getProvider()).isEqualTo("z.ai");
    assertThat(saved.getModel()).isEqualTo("glm");
  }

  @Test
  void registerAssistantMessageStoresStructuredPayload() {
    ChatSession session = new ChatSession();
    UUID sessionId = UUID.randomUUID();
    ReflectionTestUtils.setField(session, "id", sessionId);

    when(chatSessionRepository.findById(sessionId)).thenReturn(Optional.of(session));
    when(chatMessageRepository.findTopBySessionOrderBySequenceNumberDesc(session))
        .thenReturn(Optional.of(new ChatMessage(session, ChatRole.USER, "msg", 1, "z.ai", "glm")));
    when(chatMessageRepository.save(any(ChatMessage.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    ObjectMapper mapper = new ObjectMapper();
    ObjectNode payload = mapper.createObjectNode();
    payload.put("status", "success");

    chatService.registerAssistantMessage(
        sessionId, "{\"status\":\"success\"}", "z.ai", "glm", payload);

    ArgumentCaptor<ChatMessage> messageCaptor = ArgumentCaptor.forClass(ChatMessage.class);
    verify(chatMessageRepository).save(messageCaptor.capture());
    ChatMessage saved = messageCaptor.getValue();
    assertThat(saved.getStructuredPayload()).isEqualTo(payload);
  }
}
