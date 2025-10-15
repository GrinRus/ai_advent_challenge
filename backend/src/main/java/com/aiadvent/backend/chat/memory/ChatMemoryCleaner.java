package com.aiadvent.backend.chat.memory;

import com.aiadvent.backend.chat.config.ChatMemoryProperties;
import com.aiadvent.backend.chat.persistence.ChatMessageRepository;
import com.aiadvent.backend.chat.persistence.ChatSessionRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class ChatMemoryCleaner {

  private final ChatMemoryRepository chatMemoryRepository;
  private final ChatMemory chatMemory;
  private final ChatMessageRepository chatMessageRepository;
  private final ChatSessionRepository chatSessionRepository;
  private final ChatMemoryProperties properties;
  private final TaskScheduler taskScheduler;
  private final Counter evictionCounter;

  public ChatMemoryCleaner(
      ChatMemoryRepository chatMemoryRepository,
      ChatMemory chatMemory,
      ChatMessageRepository chatMessageRepository,
      ChatSessionRepository chatSessionRepository,
      ChatMemoryProperties properties,
      MeterRegistry meterRegistry,
      TaskScheduler taskScheduler) {
    this.chatMemoryRepository = chatMemoryRepository;
    this.chatMemory = chatMemory;
    this.chatMessageRepository = chatMessageRepository;
    this.chatSessionRepository = chatSessionRepository;
    this.properties = properties;
    this.taskScheduler = taskScheduler;
    this.evictionCounter =
        meterRegistry != null ? meterRegistry.counter("chat_memory_evictions_total") : null;
  }

  @PostConstruct
  void scheduleCleanup() {
    Duration interval = properties.getCleanupInterval();
    if (interval.isZero() || interval.isNegative()) {
      log.info("Chat memory cleanup scheduler disabled (interval={})", interval);
      return;
    }
    taskScheduler.scheduleWithFixedDelay(this::safeEvict, interval);
    log.info("Chat memory cleanup scheduler started with interval {}", interval);
  }

  public void evictStaleConversations() {
    Duration retention = properties.getRetention();
    if (retention.isZero() || retention.isNegative()) {
      return;
    }

    Instant threshold = Instant.now().minus(retention);
    int evicted = 0;

    for (String conversationId : chatMemoryRepository.findConversationIds()) {
      UUID sessionId = parseConversationId(conversationId);
      if (sessionId == null) {
        continue;
      }

      Optional<com.aiadvent.backend.chat.domain.ChatSession> session =
          chatSessionRepository.findById(sessionId);
      if (session.isEmpty()) {
        clearMemory(conversationId);
        evicted++;
        continue;
      }

      Optional<com.aiadvent.backend.chat.domain.ChatMessage> latestMessage =
          chatMessageRepository.findTopBySessionOrderBySequenceNumberDesc(session.get());

      if (latestMessage.isEmpty()
          || latestMessage.get().getCreatedAt() == null
          || latestMessage.get().getCreatedAt().isBefore(threshold)) {
        clearMemory(conversationId);
        evicted++;
      }
    }

    if (evicted > 0) {
      log.info("Evicted {} stale chat memory conversation windows older than {}", evicted, retention);
      if (evictionCounter != null) {
        evictionCounter.increment(evicted);
      }
    }
  }

  private void clearMemory(String conversationId) {
    chatMemory.clear(conversationId);
    chatMemoryRepository.deleteByConversationId(conversationId);
  }

  private UUID parseConversationId(String conversationId) {
    try {
      return UUID.fromString(conversationId);
    } catch (IllegalArgumentException exception) {
      log.warn("Conversation id {} is not a valid UUID, skipping cleanup", conversationId);
      return null;
    }
  }

  private void safeEvict() {
    try {
      evictStaleConversations();
    } catch (Exception exception) {
      log.warn("Chat memory cleanup task failed", exception);
    }
  }
}
