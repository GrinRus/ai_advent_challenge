package com.aiadvent.backend.flow.persistence;

import com.aiadvent.backend.flow.domain.FlowEvent;
import com.aiadvent.backend.flow.domain.FlowSession;
import com.aiadvent.backend.flow.domain.FlowStepExecution;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FlowEventRepository extends JpaRepository<FlowEvent, Long> {
  List<FlowEvent> findByFlowSessionOrderByIdAsc(FlowSession flowSession);

  List<FlowEvent> findByFlowSessionAndIdGreaterThanOrderByIdAsc(
      FlowSession flowSession, long eventId);

  List<FlowEvent> findByFlowStepExecutionOrderByIdAsc(FlowStepExecution stepExecution);

  FlowEvent findTopByFlowSessionOrderByIdDesc(FlowSession flowSession);
}
