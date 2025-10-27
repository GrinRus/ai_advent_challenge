package com.aiadvent.backend.flow.api;

import com.aiadvent.backend.flow.session.model.FlowLaunchParameters;
import com.aiadvent.backend.flow.session.model.FlowOverrides;
import com.aiadvent.backend.flow.session.model.FlowSharedContext;
import java.util.UUID;

public record FlowStartRequest(
    FlowLaunchParameters parameters,
    FlowSharedContext sharedContext,
    FlowOverrides overrides,
    UUID chatSessionId) {}
