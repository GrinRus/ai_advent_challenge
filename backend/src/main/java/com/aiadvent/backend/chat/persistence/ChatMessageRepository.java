package com.aiadvent.backend.chat.persistence;

import com.aiadvent.backend.chat.domain.ChatMessage;
import com.aiadvent.backend.chat.domain.ChatSession;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ChatMessageRepository extends JpaRepository<ChatMessage, UUID> {

  List<ChatMessage> findBySessionOrderBySequenceNumberAsc(ChatSession session);

  Optional<ChatMessage> findTopBySessionOrderBySequenceNumberDesc(ChatSession session);

  Page<ChatMessage> findBySession(ChatSession session, Pageable pageable);

  Page<ChatMessage> findBySessionAndContentContainingIgnoreCase(
      ChatSession session, String query, Pageable pageable);

  long countBySession(ChatSession session);
}
