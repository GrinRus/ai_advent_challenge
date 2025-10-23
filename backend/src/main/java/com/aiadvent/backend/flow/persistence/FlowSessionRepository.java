package com.aiadvent.backend.flow.persistence;

import com.aiadvent.backend.flow.domain.FlowDefinition;
import com.aiadvent.backend.flow.domain.FlowSession;
import com.aiadvent.backend.flow.domain.FlowSessionStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface FlowSessionRepository extends JpaRepository<FlowSession, UUID> {
  List<FlowSession> findByStatus(FlowSessionStatus status);

  Optional<FlowSession> findFirstByFlowDefinitionOrderByCreatedAtDesc(FlowDefinition flowDefinition);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select s from FlowSession s where s.id = :id")
  Optional<FlowSession> findByIdForUpdate(@Param("id") UUID id);
}
