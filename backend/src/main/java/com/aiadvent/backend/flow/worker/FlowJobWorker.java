package com.aiadvent.backend.flow.worker;

import com.aiadvent.backend.flow.config.FlowWorkerProperties;
import com.aiadvent.backend.flow.domain.FlowJob;
import com.aiadvent.backend.flow.service.AgentOrchestratorService;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@ConditionalOnProperty(prefix = "app.flow.worker", name = "enabled", havingValue = "true", matchIfMissing = true)
public class FlowJobWorker {

  private static final Logger log = LoggerFactory.getLogger(FlowJobWorker.class);

  private final AgentOrchestratorService orchestratorService;
  private final FlowWorkerProperties properties;
  private final MeterRegistry meterRegistry;
  private final ExecutorService executorService;
  private final String workerIdPrefix;

  public FlowJobWorker(
      AgentOrchestratorService orchestratorService,
      FlowWorkerProperties properties,
      MeterRegistry meterRegistry,
      @Qualifier("flowWorkerExecutor") ExecutorService executorService) {
    this.orchestratorService = orchestratorService;
    this.properties = properties;
    this.meterRegistry = meterRegistry;
    this.executorService = executorService;
    this.workerIdPrefix =
        StringUtils.hasText(properties.getWorkerIdPrefix())
            ? properties.getWorkerIdPrefix()
            : resolveDefaultWorkerId();
  }

  @Scheduled(fixedDelayString = "${app.flow.worker.poll-delay:PT0.5S}")
  public void pollQueue() {
    if (!properties.isEnabled()) {
      return;
    }
    executorService.submit(this::processSafely);
  }

  private void processSafely() {
    String workerId = workerIdPrefix + "-" + Thread.currentThread().getName();
    long start = System.nanoTime();
    String result = "empty";
    try {
      Optional<FlowJob> jobOptional = orchestratorService.processNextJob(workerId);
      if (jobOptional.isPresent()) {
        result = "processed";
        log.debug("Worker {} processed job {}", workerId, jobOptional.get().getId());
      } else {
        log.trace("Worker {} polled queue: no pending jobs", workerId);
      }
    } catch (Exception ex) {
      result = "error";
      log.error("Worker {} failed to process job", workerId, ex);
    } finally {
      long elapsed = System.nanoTime() - start;
      meterRegistry.counter("flow.job.poll.count", "result", result).increment();
      meterRegistry
          .timer("flow.job.poll.duration", "result", result)
          .record(Duration.ofNanos(elapsed));
    }
  }

  private String resolveDefaultWorkerId() {
    try {
      return InetAddress.getLocalHost().getHostName();
    } catch (UnknownHostException ex) {
      log.warn("Unable to resolve hostname for worker id, falling back to default", ex);
      return "flow-worker";
    }
  }

  @PreDestroy
  public void shutdown() {
    executorService.shutdown();
    try {
      if (!executorService.awaitTermination(2, TimeUnit.SECONDS)) {
        executorService.shutdownNow();
      }
    } catch (InterruptedException interruptedException) {
      Thread.currentThread().interrupt();
      executorService.shutdownNow();
    }
  }
}
