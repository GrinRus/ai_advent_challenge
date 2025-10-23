package com.aiadvent.backend.flow.persistence;

import com.aiadvent.backend.flow.domain.AgentDefinition;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentDefinitionRepository extends JpaRepository<AgentDefinition, UUID> {
  Optional<AgentDefinition> findByIdentifier(String identifier);
}
