package com.aiadvent.backend.flow.api;

import com.aiadvent.backend.flow.blueprint.FlowBlueprint;
import java.util.List;
import java.util.UUID;

public record FlowLaunchPreviewResponseV2(
    UUID definitionId,
    String definitionName,
    int definitionVersion,
    String description,
    FlowBlueprint blueprint,
    String startStepId,
    List<FlowLaunchPreviewResponse.Step> steps,
    FlowLaunchPreviewResponse.CostEstimate totalEstimate) {}
