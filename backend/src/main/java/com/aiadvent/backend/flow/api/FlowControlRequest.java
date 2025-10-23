package com.aiadvent.backend.flow.api;

import java.util.UUID;

public record FlowControlRequest(String command, UUID stepExecutionId) {}
