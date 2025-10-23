package com.aiadvent.backend.flow.api;

import com.aiadvent.backend.flow.domain.FlowStepStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record FlowStepDetailResponse(
    UUID sessionId,
    UUID stepExecutionId,
    String stepId,
    String stepName,
    FlowStepStatus status,
    int attempt,
    UUID agentVersionId,
    String prompt,
    Object input,
    Object output,
    Object usage,
    Object cost,
    Instant startedAt,
    Instant completedAt,
    List<FlowEventDto> events) {}
