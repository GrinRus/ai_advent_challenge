package com.aiadvent.backend.flow.persistence;

import com.aiadvent.backend.flow.domain.FlowInteractionRequest;
import com.aiadvent.backend.flow.domain.FlowInteractionStatus;
import com.aiadvent.backend.flow.domain.FlowSession;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface FlowInteractionRequestRepository
    extends JpaRepository<FlowInteractionRequest, UUID> {

  List<FlowInteractionRequest> findByFlowSessionAndStatus(
      FlowSession flowSession, FlowInteractionStatus status);

  @Query(
      "select r from FlowInteractionRequest r "
          + "join fetch r.flowStepExecution "
          + "where r.flowSession.id = :sessionId and r.status = :status")
  List<FlowInteractionRequest> findBySessionIdAndStatus(UUID sessionId, FlowInteractionStatus status);

  Optional<FlowInteractionRequest> findByFlowStepExecutionId(UUID stepExecutionId);

  List<FlowInteractionRequest> findByFlowSessionIdOrderByCreatedAtDesc(UUID flowSessionId);
}
