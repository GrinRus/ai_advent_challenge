package com.aiadvent.backend.flow.job;

import com.aiadvent.backend.flow.domain.FlowJob;
import com.aiadvent.backend.flow.domain.FlowSession;
import com.aiadvent.backend.flow.domain.FlowStepExecution;
import java.time.Instant;
import java.util.Optional;

public interface JobQueuePort {

  FlowJob enqueueStepJob(
      FlowSession flowSession,
      FlowStepExecution stepExecution,
      FlowJobPayload payload,
      Instant scheduledAt);

  Optional<FlowJob> lockNextPending(String workerId, Instant now);

  FlowJob save(FlowJob job);
}
