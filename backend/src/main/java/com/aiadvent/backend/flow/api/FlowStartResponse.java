package com.aiadvent.backend.flow.api;

import com.aiadvent.backend.flow.domain.FlowSessionStatus;
import com.aiadvent.backend.flow.session.model.FlowLaunchParameters;
import com.aiadvent.backend.flow.session.model.FlowOverrides;
import com.aiadvent.backend.flow.session.model.FlowSharedContext;
import java.time.Instant;
import java.util.UUID;

public record FlowStartResponse(
    UUID sessionId,
    FlowSessionStatus status,
    Instant startedAt,
    FlowLaunchParameters launchParameters,
    FlowSharedContext sharedContext,
    FlowOverrides overrides,
    UUID chatSessionId) {}
