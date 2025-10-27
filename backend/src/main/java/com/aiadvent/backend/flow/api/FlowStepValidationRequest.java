package com.aiadvent.backend.flow.api;

import com.aiadvent.backend.flow.blueprint.FlowBlueprint;

public record FlowStepValidationRequest(FlowBlueprint blueprint, String stepId) {}
