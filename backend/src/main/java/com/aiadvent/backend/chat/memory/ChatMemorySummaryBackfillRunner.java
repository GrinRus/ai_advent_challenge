package com.aiadvent.backend.chat.memory;

import com.aiadvent.backend.chat.config.ChatMemoryProperties;
import com.aiadvent.backend.chat.config.ChatMemoryProperties.BackfillProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Bootstraps the repeatable backfill when {@code app.chat.memory.summarization.backfill.enabled}
 * is set to {@code true}. The runner executes synchronously after the application context is ready
 * and exits once there are no more qualifying sessions.
 */
@Component
@ConditionalOnProperty(
    prefix = "app.chat.memory.summarization.backfill",
    name = "enabled",
    havingValue = "true")
public class ChatMemorySummaryBackfillRunner {

  private static final Logger log = LoggerFactory.getLogger(ChatMemorySummaryBackfillRunner.class);

  private final ChatMemoryProperties properties;
  private final ChatMemorySummaryBackfillService backfillService;

  public ChatMemorySummaryBackfillRunner(
      ChatMemoryProperties properties, ChatMemorySummaryBackfillService backfillService) {
    this.properties = properties;
    this.backfillService = backfillService;
  }

  @EventListener(ApplicationReadyEvent.class)
  public void runBackfill() {
    if (!properties.getSummarization().isEnabled()) {
      log.warn("Chat summarisation is disabled. Enable it before running the backfill worker.");
      return;
    }

    BackfillProperties config = properties.getSummarization().getBackfill();
    int iterations = 0;
    int total = 0;

    do {
      int processed = backfillService.backfillBatch();
      total += processed;
      if (processed == 0) {
        break;
      }
      iterations++;
    } while (iterations < config.getMaxIterations());

    log.info(
        "Chat memory summary backfill finished after {} iteration(s); {} session(s) were processed",
        iterations,
        total);
  }
}
