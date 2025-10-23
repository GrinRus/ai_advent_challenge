package com.aiadvent.backend.flow.persistence;

import com.aiadvent.backend.flow.domain.AgentCapability;
import com.aiadvent.backend.flow.domain.AgentVersion;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentCapabilityRepository extends JpaRepository<AgentCapability, Long> {
  List<AgentCapability> findByAgentVersion(AgentVersion version);
}
