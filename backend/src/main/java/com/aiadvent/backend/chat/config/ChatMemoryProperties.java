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

  private SummarizationProperties summarization = new SummarizationProperties();

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

  public SummarizationProperties getSummarization() {
    return summarization;
  }

  public void setSummarization(SummarizationProperties summarization) {
    this.summarization = summarization;
  }

  public static class SummarizationProperties {

    /**
     * Enables LLM-based summarisation of long conversations before they are sent to a provider.
     */
    private boolean enabled = false;

    /**
     * Maximum number of tokens allowed in the assembled prompt before the preflight check requests
     * a summary.
     */
    private int triggerTokenLimit = 12000;

    /**
     * Target number of tokens after summarisation. The service trims history until the estimate
     * goes below this number.
     */
    private int targetTokenCount = 6000;

    /**
     * Identifier of the model used for summarisation.
     */
    private String model = "openai:gpt-4o-mini";

    /**
     * Maximum number of concurrent summarisation jobs executed at the same time.
     */
    private int maxConcurrentSummaries = 4;

    /**
     * Maximum number of summarisation jobs waiting in the queue before new requests are rejected.
     */
    private int maxQueueSize = 100;

    /**
     * Number of most recent messages that should remain verbatim after summarisation. Older
     * messages are condensed into summary entries. Set to a non-positive value to retain the
     * entire transcript.
     */
    private int retainedMessages = 200;

    /**
     * Configuration for the repeatable backfill script that populates initial summaries for long
     * sessions recorded before the online worker was enabled.
     */
    private BackfillProperties backfill = new BackfillProperties();

    public boolean isEnabled() {
      return enabled;
    }

    public void setEnabled(boolean enabled) {
      this.enabled = enabled;
    }

    public int getTriggerTokenLimit() {
      return triggerTokenLimit;
    }

    public void setTriggerTokenLimit(int triggerTokenLimit) {
      this.triggerTokenLimit = triggerTokenLimit;
    }

    public int getTargetTokenCount() {
      return targetTokenCount;
    }

    public void setTargetTokenCount(int targetTokenCount) {
      this.targetTokenCount = targetTokenCount;
    }

    public String getModel() {
      return model;
    }

    public void setModel(String model) {
      this.model = model;
    }

    public int getMaxConcurrentSummaries() {
      return maxConcurrentSummaries;
    }

    public void setMaxConcurrentSummaries(int maxConcurrentSummaries) {
      this.maxConcurrentSummaries = maxConcurrentSummaries;
    }

    public int getMaxQueueSize() {
      return maxQueueSize;
    }

    public void setMaxQueueSize(int maxQueueSize) {
      this.maxQueueSize = maxQueueSize;
    }

    public BackfillProperties getBackfill() {
      return backfill;
    }

    public void setBackfill(BackfillProperties backfill) {
      this.backfill = backfill;
    }

    public int getRetainedMessages() {
      return retainedMessages;
    }

    public void setRetainedMessages(int retainedMessages) {
      this.retainedMessages = retainedMessages;
    }
  }

  public static class BackfillProperties {

    /**
     * Enables the repeatable backfill runner. The runner is invoked manually and does not execute
     * in regular application modes unless explicitly enabled.
     */
    private boolean enabled = false;

    /**
     * Minimum number of windowed messages that a chat session must contain before it becomes a
     * candidate for backfill summarisation.
     */
    private int minMessages = 40;

    /**
     * Maximum number of sessions processed in a single iteration. The runner keeps executing until
     * no candidates remain or {@link #maxIterations} is reached.
     */
    private int batchSize = 25;

    /**
     * Guards against accidental infinite loops when new sessions are being created while the
     * backfill script is running.
     */
    private int maxIterations = 20;

    public boolean isEnabled() {
      return enabled;
    }

    public void setEnabled(boolean enabled) {
      this.enabled = enabled;
    }

    public int getMinMessages() {
      return minMessages;
    }

    public void setMinMessages(int minMessages) {
      this.minMessages = minMessages;
    }

    public int getBatchSize() {
      return batchSize;
    }

    public void setBatchSize(int batchSize) {
      this.batchSize = batchSize;
    }

    public int getMaxIterations() {
      return maxIterations;
    }

    public void setMaxIterations(int maxIterations) {
      this.maxIterations = maxIterations;
    }
  }
}
