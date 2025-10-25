package com.aiadvent.backend.flow.persistence;

import com.aiadvent.backend.flow.domain.FlowMemorySummary;
import com.aiadvent.backend.flow.domain.FlowSession;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FlowMemorySummaryRepository extends JpaRepository<FlowMemorySummary, UUID> {

  List<FlowMemorySummary> findByFlowSessionAndChannelOrderBySourceVersionStart(FlowSession flowSession, String channel);

  Optional<FlowMemorySummary> findFirstByFlowSessionAndChannelOrderBySourceVersionEndDesc(
      FlowSession flowSession, String channel);

  void deleteByFlowSessionAndChannel(FlowSession flowSession, String channel);
}
