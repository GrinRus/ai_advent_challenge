package com.aiadvent.backend.flow.persistence;

import com.aiadvent.backend.flow.domain.FlowSession;
import com.aiadvent.backend.flow.domain.FlowStepExecution;
import com.aiadvent.backend.flow.domain.FlowStepStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FlowStepExecutionRepository extends JpaRepository<FlowStepExecution, UUID> {
  List<FlowStepExecution> findByFlowSessionOrderByCreatedAtAsc(FlowSession flowSession);

  Optional<FlowStepExecution> findFirstByFlowSessionAndStepIdOrderByAttemptDesc(
      FlowSession flowSession, String stepId);

  List<FlowStepExecution> findByFlowSessionAndStatus(
      FlowSession flowSession, FlowStepStatus status);
}
