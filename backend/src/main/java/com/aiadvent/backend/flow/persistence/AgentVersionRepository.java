package com.aiadvent.backend.flow.persistence;

import com.aiadvent.backend.flow.domain.AgentDefinition;
import com.aiadvent.backend.flow.domain.AgentVersion;
import com.aiadvent.backend.flow.domain.AgentVersionStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentVersionRepository extends JpaRepository<AgentVersion, UUID> {
  Optional<AgentVersion> findByAgentDefinitionAndVersion(AgentDefinition definition, int version);

  List<AgentVersion> findByAgentDefinitionAndStatus(
      AgentDefinition definition, AgentVersionStatus status);

  List<AgentVersion> findByAgentDefinitionOrderByVersionDesc(AgentDefinition definition);

  Optional<AgentVersion> findTopByAgentDefinitionOrderByVersionDesc(AgentDefinition definition);

  Optional<AgentVersion> findTopByAgentDefinitionAndStatusOrderByVersionDesc(
      AgentDefinition definition, AgentVersionStatus status);
}
