package com.aiadvent.backend.chat.persistence;

import com.aiadvent.backend.chat.domain.ChatSession;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ChatSessionRepository extends JpaRepository<ChatSession, UUID> {}
