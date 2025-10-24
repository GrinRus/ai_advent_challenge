package com.aiadvent.backend.flow.persistence;

import com.aiadvent.backend.flow.domain.FlowInteractionRequest;
import com.aiadvent.backend.flow.domain.FlowInteractionResponse;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FlowInteractionResponseRepository
    extends JpaRepository<FlowInteractionResponse, UUID> {

  Optional<FlowInteractionResponse> findFirstByRequestOrderByCreatedAtDesc(
      FlowInteractionRequest request);

  boolean existsByRequest(FlowInteractionRequest request);
}
