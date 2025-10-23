package com.aiadvent.backend.flow.telemetry;

import com.aiadvent.backend.chat.provider.model.UsageCostEstimate;
import com.aiadvent.backend.flow.domain.FlowSessionStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class FlowTelemetryService {

  private static final Logger log = LoggerFactory.getLogger(FlowTelemetryService.class);

  private final MeterRegistry meterRegistry;
  private final AtomicInteger activeSessions;
  private final Timer stepDurationTimer;
  private final Counter retryCounter;
  private final DistributionSummary costSummary;
  private final Map<UUID, Long> sessionActiveGauge;

  public FlowTelemetryService(MeterRegistry meterRegistry) {
    this.meterRegistry = meterRegistry;
    this.activeSessions = new AtomicInteger();
    this.sessionActiveGauge = new ConcurrentHashMap<>();
    if (meterRegistry != null) {
      meterRegistry.gauge("flow_sessions_active", activeSessions);
      this.stepDurationTimer = meterRegistry.timer("flow_step_duration");
      this.retryCounter = meterRegistry.counter("flow_retry_count");
      this.costSummary = meterRegistry.summary("flow_cost_usd");
    } else {
      SimpleMeterRegistry noopRegistry = new SimpleMeterRegistry();
      this.stepDurationTimer = Timer.builder("flow_step_duration").register(noopRegistry);
      this.retryCounter = Counter.builder("flow_retry_count").register(noopRegistry);
      this.costSummary = DistributionSummary.builder("flow_cost_usd").register(noopRegistry);
    }
  }

  public void sessionStarted(UUID sessionId, UUID definitionId, int definitionVersion) {
    activeSessions.incrementAndGet();
    sessionActiveGauge.put(sessionId, System.currentTimeMillis());
    log.info(
        "Flow session {} started for definition {} v{}",
        sessionId,
        definitionId,
        definitionVersion);
  }

  public void sessionCompleted(UUID sessionId, FlowSessionStatus status, Duration duration) {
    if (sessionActiveGauge.remove(sessionId) != null) {
      activeSessions.decrementAndGet();
    }
    if (duration != null) {
      stepDurationTimer.record(duration);
    }
    log.info("Flow session {} finished with status {}", sessionId, status);
  }

  public void stepStarted(UUID sessionId, UUID stepExecutionId, String stepId, int attempt) {
    log.info(
        "Flow session {} step {} attempt {} started (execution {})",
        sessionId,
        stepId,
        attempt,
        stepExecutionId);
  }

  public void stepCompleted(
      UUID sessionId,
      UUID stepExecutionId,
      String stepId,
      int attempt,
      Duration duration,
      UsageCostEstimate usageCost) {
    if (duration != null) {
      stepDurationTimer.record(duration);
    }
    if (usageCost != null && usageCost.totalCost() != null) {
      costSummary.record(usageCost.totalCost().doubleValue());
    }
    log.info(
        "Flow session {} step {} attempt {} completed in {} ms (execution {})",
        sessionId,
        stepId,
        attempt,
        duration != null ? duration.toMillis() : "n/a",
        stepExecutionId);
  }

  public void stepFailed(
      UUID sessionId,
      UUID stepExecutionId,
      String stepId,
      int attempt,
      Duration duration,
      Throwable error) {
    if (duration != null) {
      stepDurationTimer.record(duration);
    }
    log.warn(
        "Flow session {} step {} attempt {} failed (execution {}): {}",
        sessionId,
        stepId,
        attempt,
        stepExecutionId,
        error != null ? error.getMessage() : "unknown");
  }

  public void retryScheduled(UUID sessionId, String stepId, int attempt) {
    retryCounter.increment();
    log.info(
        "Flow session {} step {} retry scheduled for attempt {}",
        sessionId,
        stepId,
        attempt);
  }

  public void sessionEvent(UUID sessionId, String event, String message) {
    if (message != null && !message.isBlank()) {
      log.info("Flow session {} event {}: {}", sessionId, event, message);
    } else {
      log.info("Flow session {} event {}", sessionId, event);
    }
  }
}
