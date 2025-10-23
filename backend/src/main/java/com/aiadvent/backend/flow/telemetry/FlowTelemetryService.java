package com.aiadvent.backend.flow.telemetry;

import com.aiadvent.backend.chat.provider.model.UsageCostEstimate;
import com.aiadvent.backend.flow.domain.FlowSessionStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.atomic.DoubleAdder;
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
  private final Map<UUID, SessionTelemetryMetrics> sessionTelemetry;

  public FlowTelemetryService(MeterRegistry meterRegistry) {
    this.meterRegistry = meterRegistry;
    this.activeSessions = new AtomicInteger();
    this.sessionActiveGauge = new ConcurrentHashMap<>();
    this.sessionTelemetry = new ConcurrentHashMap<>();
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
    sessionTelemetry.put(sessionId, new SessionTelemetryMetrics(Instant.now()));
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
    sessionTelemetry.computeIfPresent(
        sessionId,
        (id, telemetry) -> {
          telemetry.markCompleted(status, Instant.now());
          return telemetry;
        });
    log.info("Flow session {} finished with status {}", sessionId, status);
  }

  public void stepStarted(UUID sessionId, UUID stepExecutionId, String stepId, int attempt) {
    log.info(
        "Flow session {} step {} attempt {} started (execution {})",
        sessionId,
        stepId,
        attempt,
        stepExecutionId);
    sessionTelemetry.computeIfPresent(
        sessionId,
        (id, telemetry) -> {
          telemetry.touch(Instant.now());
          return telemetry;
        });
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
    sessionTelemetry.computeIfPresent(
        sessionId,
        (id, telemetry) -> {
          telemetry.onStepCompleted(usageCost, Instant.now());
          return telemetry;
        });
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
    sessionTelemetry.computeIfPresent(
        sessionId,
        (id, telemetry) -> {
          telemetry.onStepFailed(Instant.now());
          return telemetry;
        });
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
    sessionTelemetry.computeIfPresent(
        sessionId,
        (id, telemetry) -> {
          telemetry.onRetryScheduled(Instant.now());
          return telemetry;
        });
    log.info(
        "Flow session {} step {} retry scheduled for attempt {}",
        sessionId,
        stepId,
        attempt);
  }

  public void sessionEvent(UUID sessionId, String event, String message) {
    sessionTelemetry.computeIfPresent(
        sessionId,
        (id, telemetry) -> {
          telemetry.touch(Instant.now());
          return telemetry;
        });
    if (message != null && !message.isBlank()) {
      log.info("Flow session {} event {}: {}", sessionId, event, message);
    } else {
      log.info("Flow session {} event {}", sessionId, event);
    }
  }

  public Optional<FlowTelemetrySnapshot> snapshot(UUID sessionId) {
    SessionTelemetryMetrics metrics = sessionTelemetry.get(sessionId);
    if (metrics == null) {
      return Optional.empty();
    }
    return Optional.of(metrics.toSnapshot());
  }

  private static final class SessionTelemetryMetrics {
    private final Instant startedAt;
    private final AtomicInteger stepsCompleted = new AtomicInteger();
    private final AtomicInteger stepsFailed = new AtomicInteger();
    private final AtomicInteger retriesScheduled = new AtomicInteger();
    private final DoubleAdder totalCostUsd = new DoubleAdder();
    private final LongAdder promptTokens = new LongAdder();
    private final LongAdder completionTokens = new LongAdder();
    private final AtomicReference<Instant> lastUpdated = new AtomicReference<>();
    private final AtomicReference<Instant> completedAt = new AtomicReference<>();
    private final AtomicReference<FlowSessionStatus> terminalStatus = new AtomicReference<>(FlowSessionStatus.RUNNING);

    private SessionTelemetryMetrics(Instant startedAt) {
      this.startedAt = startedAt;
      this.lastUpdated.set(startedAt);
    }

    private SessionTelemetryMetrics touch(Instant instant) {
      lastUpdated.set(instant);
      return this;
    }

    private void onStepCompleted(UsageCostEstimate usageCost, Instant instant) {
      stepsCompleted.incrementAndGet();
      if (usageCost != null) {
        if (usageCost.totalCost() != null) {
          totalCostUsd.add(usageCost.totalCost().doubleValue());
        }
        if (usageCost.promptTokens() != null) {
          promptTokens.add(usageCost.promptTokens());
        }
        if (usageCost.completionTokens() != null) {
          completionTokens.add(usageCost.completionTokens());
        }
      }
      touch(instant);
    }

    private void onStepFailed(Instant instant) {
      stepsFailed.incrementAndGet();
      touch(instant);
    }

    private void onRetryScheduled(Instant instant) {
      retriesScheduled.incrementAndGet();
      touch(instant);
    }

    private void markCompleted(FlowSessionStatus status, Instant instant) {
      terminalStatus.set(status);
      completedAt.set(instant);
      touch(instant);
    }

    private FlowTelemetrySnapshot toSnapshot() {
      return new FlowTelemetrySnapshot(
          stepsCompleted.get(),
          stepsFailed.get(),
          retriesScheduled.get(),
          totalCostUsd.sum(),
          promptTokens.sum(),
          completionTokens.sum(),
          startedAt,
          lastUpdated.get(),
          completedAt.get(),
          terminalStatus.get());
    }
  }

  public record FlowTelemetrySnapshot(
      int stepsCompleted,
      int stepsFailed,
      int retriesScheduled,
      double totalCostUsd,
      long promptTokens,
      long completionTokens,
      Instant startedAt,
      Instant lastUpdated,
      Instant completedAt,
      FlowSessionStatus status) {}
}
