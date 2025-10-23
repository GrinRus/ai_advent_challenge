package com.aiadvent.backend.flow.job;

import com.aiadvent.backend.flow.domain.FlowJob;
import com.aiadvent.backend.flow.domain.FlowJobStatus;
import com.aiadvent.backend.flow.domain.FlowSession;
import com.aiadvent.backend.flow.domain.FlowStepExecution;
import com.aiadvent.backend.flow.persistence.FlowJobRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class PostgresJobQueueAdapter implements JobQueuePort {

  private final FlowJobRepository flowJobRepository;
  private final ObjectMapper objectMapper;

  public PostgresJobQueueAdapter(FlowJobRepository flowJobRepository, ObjectMapper objectMapper) {
    this.flowJobRepository = flowJobRepository;
    this.objectMapper = objectMapper;
  }

  @Override
  @Transactional
  public FlowJob enqueueStepJob(
      FlowSession flowSession,
      FlowStepExecution stepExecution,
      FlowJobPayload payload,
      Instant scheduledAt) {
    ObjectNode payloadNode = objectMapper.valueToTree(payload);
    FlowJob job = new FlowJob(payloadNode, FlowJobStatus.PENDING);
    job.setFlowSession(flowSession);
    job.setFlowStepExecution(stepExecution);
    job.setScheduledAt(scheduledAt);
    job.setRetryCount(payload.attempt() - 1);
    return flowJobRepository.save(job);
  }

  @Override
  @Transactional
  public Optional<FlowJob> lockNextPending(String workerId, Instant now) {
    Optional<FlowJob> jobOptional = flowJobRepository.lockNextJob(FlowJobStatus.PENDING, now);
    jobOptional.ifPresent(
        job -> {
          job.setStatus(FlowJobStatus.RUNNING);
          job.setLockedAt(now);
          job.setLockedBy(workerId);
          flowJobRepository.save(job);
        });
    return jobOptional;
  }

  @Override
  @Transactional
  public FlowJob save(FlowJob job) {
    return flowJobRepository.save(job);
  }
}
