package com.aiadvent.backend.flow.api;

import com.aiadvent.backend.flow.domain.FlowEventType;
import java.math.BigDecimal;
import java.time.Instant;

public record FlowEventDto(
    long eventId,
    FlowEventType type,
    String status,
    String traceId,
    String spanId,
    BigDecimal cost,
    Integer tokensPrompt,
    Integer tokensCompletion,
    Instant createdAt,
    Object payload) {}
