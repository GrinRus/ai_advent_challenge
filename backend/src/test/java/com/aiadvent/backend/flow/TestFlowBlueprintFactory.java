package com.aiadvent.backend.flow;

import com.aiadvent.backend.flow.blueprint.FlowBlueprint;
import com.aiadvent.backend.flow.blueprint.FlowBlueprintLaunchParameter;
import com.aiadvent.backend.flow.blueprint.FlowBlueprintMemory;
import com.aiadvent.backend.flow.blueprint.FlowBlueprintMetadata;
import com.aiadvent.backend.flow.blueprint.FlowBlueprintStep;
import com.aiadvent.backend.flow.blueprint.FlowStepTransitionsDraft;
import java.util.List;
import java.util.UUID;

public final class TestFlowBlueprintFactory {

  private TestFlowBlueprintFactory() {}

  public static FlowBlueprint simpleBlueprint() {
    return simpleBlueprint(UUID.randomUUID());
  }

  public static FlowBlueprint simpleBlueprint(UUID agentVersionId) {
    FlowBlueprintStep step =
        new FlowBlueprintStep(
            "step-1",
            "Bootstrap",
            agentVersionId.toString(),
            "Initial prompt",
            null,
            null,
            List.of(),
            List.of(),
            new FlowStepTransitionsDraft(new FlowStepTransitionsDraft.Success(null, true), null),
            1);
    return new FlowBlueprint(
        1,
        new FlowBlueprintMetadata("Test flow", null, List.of()),
        null,
        null,
        step.id(),
        true,
        List.<FlowBlueprintLaunchParameter>of(),
        FlowBlueprintMemory.empty(),
        List.of(step));
  }
}
