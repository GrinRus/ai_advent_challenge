package com.aiadvent.backend.flow.persistence;

import com.aiadvent.backend.flow.domain.FlowDefinition;
import com.aiadvent.backend.flow.domain.FlowDefinitionStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FlowDefinitionRepository extends JpaRepository<FlowDefinition, UUID> {
  Optional<FlowDefinition> findByNameAndVersion(String name, int version);

  List<FlowDefinition> findByNameOrderByVersionDesc(String name);

  List<FlowDefinition> findByStatus(FlowDefinitionStatus status);

  List<FlowDefinition> findAllByOrderByUpdatedAtDesc();
}
