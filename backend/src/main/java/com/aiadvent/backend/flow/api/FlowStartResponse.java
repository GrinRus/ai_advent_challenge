package com.aiadvent.backend.flow.api;

import com.aiadvent.backend.flow.domain.FlowSessionStatus;
import java.time.Instant;
import java.util.UUID;

public record FlowStartResponse(UUID sessionId, FlowSessionStatus status, Instant startedAt) {}
