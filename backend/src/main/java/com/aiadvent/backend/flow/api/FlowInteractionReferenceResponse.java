package com.aiadvent.backend.flow.api;

import com.aiadvent.backend.flow.domain.FlowInteractionType;
import java.util.List;

public record FlowInteractionReferenceResponse(List<InteractionScheme> schemes) {

  public record InteractionScheme(
      FlowInteractionType type, String displayName, String description, String schemaHint) {}
}
