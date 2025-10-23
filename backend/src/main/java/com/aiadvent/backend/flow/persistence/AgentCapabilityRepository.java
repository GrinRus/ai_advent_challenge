package com.aiadvent.backend.flow.persistence;

import com.aiadvent.backend.flow.domain.AgentCapability;
import com.aiadvent.backend.flow.domain.AgentVersion;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AgentCapabilityRepository extends JpaRepository<AgentCapability, Long> {

  List<AgentCapability> findByAgentVersionOrderByIdAsc(AgentVersion version);

  @Modifying
  @Query("delete from AgentCapability ac where ac.agentVersion = :version")
  void deleteByAgentVersion(@Param("version") AgentVersion version);
}
