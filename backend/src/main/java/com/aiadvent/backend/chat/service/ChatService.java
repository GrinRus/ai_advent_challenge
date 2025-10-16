package com.aiadvent.backend.chat.service;

import com.aiadvent.backend.chat.domain.ChatMessage;
import com.aiadvent.backend.chat.domain.ChatRole;
import com.aiadvent.backend.chat.domain.ChatSession;
import com.aiadvent.backend.chat.persistence.ChatMessageRepository;
import com.aiadvent.backend.chat.persistence.ChatSessionRepository;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ChatService {

  private final ChatSessionRepository chatSessionRepository;
  private final ChatMessageRepository chatMessageRepository;

  public ChatService(
      ChatSessionRepository chatSessionRepository, ChatMessageRepository chatMessageRepository) {
    this.chatSessionRepository = chatSessionRepository;
    this.chatMessageRepository = chatMessageRepository;
  }

  @Transactional
  public ConversationContext registerUserMessage(
      UUID sessionId, String content, String provider, String model) {
    if (!StringUtils.hasText(content)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Message must not be empty");
    }

    boolean newSession = false;
    ChatSession session;

    if (sessionId == null) {
      session = chatSessionRepository.save(new ChatSession());
      newSession = true;
    } else {
      session =
          chatSessionRepository
              .findById(sessionId)
              .orElseThrow(
                  () ->
                      new ResponseStatusException(
                          HttpStatus.NOT_FOUND, "Chat session not found: " + sessionId));
    }

    int nextSequence = nextSequenceNumber(session);
    chatMessageRepository.save(
        new ChatMessage(session, ChatRole.USER, content, nextSequence, provider, model));

    return new ConversationContext(session.getId(), newSession);
  }

  @Transactional
  public void registerAssistantMessage(UUID sessionId, String content, String provider, String model) {
    if (!StringUtils.hasText(content)) {
      return;
    }

    ChatSession session =
        chatSessionRepository
            .findById(sessionId)
            .orElseThrow(
                () ->
                    new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Chat session not found: " + sessionId));

    int nextSequence = nextSequenceNumber(session);
    chatMessageRepository.save(
        new ChatMessage(session, ChatRole.ASSISTANT, content, nextSequence, provider, model));
  }

  private int nextSequenceNumber(ChatSession session) {
    return chatMessageRepository
            .findTopBySessionOrderBySequenceNumberDesc(session)
            .map(ChatMessage::getSequenceNumber)
            .orElse(0)
        + 1;
  }
}
