package com.aiadvent.backend.flow.persistence;

import com.aiadvent.backend.flow.domain.FlowDefinition;
import com.aiadvent.backend.flow.domain.FlowDefinitionHistory;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FlowDefinitionHistoryRepository extends JpaRepository<FlowDefinitionHistory, Long> {
  List<FlowDefinitionHistory> findByFlowDefinitionOrderByVersionDesc(FlowDefinition flowDefinition);
}
