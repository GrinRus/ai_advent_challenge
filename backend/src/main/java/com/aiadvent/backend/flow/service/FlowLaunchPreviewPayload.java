package com.aiadvent.backend.flow.service;

import com.aiadvent.backend.flow.api.FlowLaunchPreviewResponse;
import com.aiadvent.backend.flow.blueprint.FlowBlueprint;
import java.util.List;
import java.util.UUID;

public record FlowLaunchPreviewPayload(
    UUID definitionId,
    String definitionName,
    int definitionVersion,
    String description,
    FlowBlueprint blueprint,
    String startStepId,
    List<FlowLaunchPreviewResponse.Step> steps,
    FlowLaunchPreviewResponse.CostEstimate totalEstimate) {}
