package com.aiadvent.backend.flow.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aiadvent.backend.flow.config.FlowWorkerProperties;
import com.aiadvent.backend.flow.domain.FlowJob;
import com.aiadvent.backend.flow.service.AgentOrchestratorService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FlowJobWorkerTest {

  @Mock private AgentOrchestratorService orchestratorService;

  private SimpleMeterRegistry meterRegistry;
  private FlowWorkerProperties properties;
  private ExecutorService executorService;
  private FlowJobWorker worker;

  @BeforeEach
  void setUp() {
    meterRegistry = new SimpleMeterRegistry();
    properties = new FlowWorkerProperties();
    properties.setEnabled(true);
    properties.setWorkerIdPrefix("test-worker");
    executorService = new DirectExecutorService();
    worker = new FlowJobWorker(orchestratorService, properties, meterRegistry, executorService);
  }

  @AfterEach
  void tearDown() {
    worker.shutdown();
    meterRegistry.close();
  }

  @Test
  void pollsQueueAndProcessesJob() {
    FlowJob job = org.mockito.Mockito.mock(FlowJob.class);
    when(job.getId()).thenReturn(42L);
    when(orchestratorService.processNextJob(anyString())).thenReturn(Optional.of(job));

    worker.pollQueue();

    ArgumentCaptor<String> workerCaptor = ArgumentCaptor.forClass(String.class);
    verify(orchestratorService).processNextJob(workerCaptor.capture());
    assertThat(workerCaptor.getValue()).startsWith("test-worker-");
    assertThat(meterRegistry.counter("flow.job.poll.count", "result", "processed").count())
        .isEqualTo(1.0d);
  }

  @Test
  void pollsQueueAndHandlesEmptyResult() {
    when(orchestratorService.processNextJob(anyString())).thenReturn(Optional.empty());

    worker.pollQueue();

    ArgumentCaptor<String> workerCaptor = ArgumentCaptor.forClass(String.class);
    verify(orchestratorService).processNextJob(workerCaptor.capture());
    assertThat(workerCaptor.getValue()).startsWith("test-worker-");
    assertThat(meterRegistry.counter("flow.job.poll.count", "result", "empty").count())
        .isEqualTo(1.0d);
  }

  @Test
  void disabledWorkerDoesNotInvokeService() {
    FlowWorkerProperties disabled = new FlowWorkerProperties();
    disabled.setEnabled(false);
    disabled.setWorkerIdPrefix("disabled");
    FlowJobWorker disabledWorker =
        new FlowJobWorker(orchestratorService, disabled, meterRegistry, executorService);

    disabledWorker.pollQueue();

    verify(orchestratorService, never()).processNextJob(anyString());
    assertThat(meterRegistry.find("flow.job.poll.count").counter()).isNull();
    disabledWorker.shutdown();
  }

  @Test
  void recordsErrorWhenProcessingFails() {
    when(orchestratorService.processNextJob(anyString()))
        .thenThrow(new IllegalStateException("boom"));

    worker.pollQueue();

    ArgumentCaptor<String> workerCaptor = ArgumentCaptor.forClass(String.class);
    verify(orchestratorService).processNextJob(workerCaptor.capture());
    assertThat(workerCaptor.getValue()).startsWith("test-worker-");
    assertThat(meterRegistry.counter("flow.job.poll.count", "result", "error").count())
        .isEqualTo(1.0d);
  }

  private static final class DirectExecutorService extends AbstractExecutorService {

    private volatile boolean shutdown;

    @Override
    public void shutdown() {
      shutdown = true;
    }

    @Override
    public List<Runnable> shutdownNow() {
      shutdown = true;
      return Collections.emptyList();
    }

    @Override
    public boolean isShutdown() {
      return shutdown;
    }

    @Override
    public boolean isTerminated() {
      return shutdown;
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) {
      return shutdown;
    }

    @Override
    public void execute(Runnable command) {
      command.run();
    }
  }
}
