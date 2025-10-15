package com.aiadvent.backend.chat.memory;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.stereotype.Component;

@Component
public class ChatMemoryMetrics {

  public ChatMemoryMetrics(ChatMemoryRepository repository, MeterRegistry meterRegistry) {
    if (meterRegistry != null) {
      meterRegistry.gauge(
          "chat_memory_conversations",
          repository,
          value -> (double) value.findConversationIds().size());
    }
  }
}
