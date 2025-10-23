package com.aiadvent.backend.flow.persistence;

import com.aiadvent.backend.flow.domain.FlowMemoryVersion;
import com.aiadvent.backend.flow.domain.FlowSession;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FlowMemoryVersionRepository extends JpaRepository<FlowMemoryVersion, Long> {
  Optional<FlowMemoryVersion> findFirstByFlowSessionAndChannelOrderByVersionDesc(
      FlowSession flowSession, String channel);

  Optional<FlowMemoryVersion> findByFlowSessionAndChannelAndVersion(
      FlowSession flowSession, String channel, long version);

  List<FlowMemoryVersion> findByFlowSessionAndChannelOrderByVersionDesc(
      FlowSession flowSession, String channel);
}
