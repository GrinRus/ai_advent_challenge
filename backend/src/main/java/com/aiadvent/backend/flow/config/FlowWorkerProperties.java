package com.aiadvent.backend.flow.config;

import jakarta.validation.constraints.Min;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.flow.worker")
public class FlowWorkerProperties {

  private boolean enabled = true;
  private Duration pollDelay = Duration.ofMillis(500);

  @Min(1)
  private int maxConcurrency = 1;

  private String workerIdPrefix;

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public Duration getPollDelay() {
    return pollDelay;
  }

  public void setPollDelay(Duration pollDelay) {
    if (pollDelay != null && !pollDelay.isNegative() && !pollDelay.isZero()) {
      this.pollDelay = pollDelay;
    }
  }

  public int getMaxConcurrency() {
    return Math.max(1, maxConcurrency);
  }

  public void setMaxConcurrency(int maxConcurrency) {
    this.maxConcurrency = Math.max(1, maxConcurrency);
  }

  public String getWorkerIdPrefix() {
    return workerIdPrefix;
  }

  public void setWorkerIdPrefix(String workerIdPrefix) {
    this.workerIdPrefix = workerIdPrefix;
  }
}
