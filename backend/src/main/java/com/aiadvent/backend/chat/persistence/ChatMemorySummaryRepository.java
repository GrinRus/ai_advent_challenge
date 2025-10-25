package com.aiadvent.backend.chat.persistence;

import com.aiadvent.backend.chat.domain.ChatMemorySummary;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatMemorySummaryRepository extends JpaRepository<ChatMemorySummary, UUID> {

  List<ChatMemorySummary> findBySessionIdOrderBySourceStartOrder(UUID sessionId);
}
