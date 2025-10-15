package com.aiadvent.backend.chat.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.chat.memory")
public class ChatMemoryProperties {

  /**
   * Maximum number of messages stored in the sliding window that is sent to the LLM
   * on each request.
   */
  private int windowSize = 20;

  /**
   * How long a conversation is kept in the chat memory window once it becomes idle.
   * After the threshold is reached, the window entries are evicted but the full
   * history remains in the database.
   */
  private Duration retention = Duration.ofHours(6);

  /**
   * How often the scheduled cleanup task checks for stale conversations in the chat
   * memory repository.
   */
  private Duration cleanupInterval = Duration.ofMinutes(30);

  public int getWindowSize() {
    return windowSize;
  }

  public void setWindowSize(int windowSize) {
    this.windowSize = windowSize;
  }

  public Duration getRetention() {
    return retention;
  }

  public void setRetention(Duration retention) {
    this.retention = retention;
  }

  public Duration getCleanupInterval() {
    return cleanupInterval;
  }

  public void setCleanupInterval(Duration cleanupInterval) {
    this.cleanupInterval = cleanupInterval;
  }
}
