package com.aiadvent.backend.flow.blueprint;

import com.aiadvent.backend.flow.config.FlowStepConfig;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@JsonIgnoreProperties(ignoreUnknown = true)
public final class FlowBlueprint {

  private final Integer schemaVersion;
  private final FlowBlueprintMetadata metadata;
  private final String startStepId;
  private final boolean syncOnly;
  private final List<FlowBlueprintLaunchParameter> launchParameters;
  private final FlowBlueprintMemory memory;
  private final List<FlowBlueprintStep> steps;
  @JsonIgnore private final Map<String, FlowBlueprintStep> stepsIndex;

  @JsonCreator
  public FlowBlueprint(
      @JsonProperty("schemaVersion") Integer schemaVersion,
      @JsonProperty("metadata") FlowBlueprintMetadata metadata,
      @JsonProperty("title") String legacyTitle,
      @JsonProperty("description") String legacyDescription,
      @JsonProperty("startStepId") String startStepId,
      @JsonProperty("syncOnly") Boolean syncOnly,
      @JsonProperty("launchParameters") List<FlowBlueprintLaunchParameter> launchParameters,
      @JsonProperty("memory") FlowBlueprintMemory memory,
      @JsonProperty("steps") List<FlowBlueprintStep> steps) {

    FlowBlueprintMetadata resolvedMetadata =
        (metadata != null ? metadata : FlowBlueprintMetadata.empty())
            .withTitleIfEmpty(legacyTitle)
            .withDescriptionIfEmpty(legacyDescription);

    List<FlowBlueprintLaunchParameter> safeLaunchParameters =
        launchParameters != null ? new ArrayList<>(launchParameters) : new ArrayList<>();
    List<FlowBlueprintStep> safeSteps = steps != null ? new ArrayList<>(steps) : new ArrayList<>();

    if (safeSteps.isEmpty()) {
      throw new IllegalArgumentException("Flow blueprint must contain at least one step");
    }

    Map<String, FlowBlueprintStep> index = new LinkedHashMap<>();
    for (FlowBlueprintStep step : safeSteps) {
      if (index.putIfAbsent(step.id(), step) != null) {
        throw new IllegalArgumentException("Duplicate step id detected: " + step.id());
      }
    }

    String resolvedStartStepId = startStepId;
    if (resolvedStartStepId == null || resolvedStartStepId.isBlank()) {
      resolvedStartStepId = safeSteps.get(0).id();
    } else if (!index.containsKey(resolvedStartStepId)) {
      throw new IllegalArgumentException(
          "Start step id " + resolvedStartStepId + " is not present in blueprint steps");
    }

    this.schemaVersion = schemaVersion != null ? schemaVersion : 1;
    this.metadata = resolvedMetadata;
    this.startStepId = resolvedStartStepId;
    this.syncOnly = syncOnly == null || syncOnly;
    this.launchParameters = List.copyOf(safeLaunchParameters);
    this.memory = memory != null ? memory : FlowBlueprintMemory.empty();
    this.steps = List.copyOf(safeSteps);
    this.stepsIndex = Map.copyOf(index);
  }

  @JsonProperty("schemaVersion")
  public Integer schemaVersion() {
    return schemaVersion;
  }

  @JsonProperty("metadata")
  public FlowBlueprintMetadata metadata() {
    return metadata;
  }

  @JsonProperty("startStepId")
  public String startStepId() {
    return startStepId;
  }

  @JsonProperty("syncOnly")
  public boolean syncOnly() {
    return syncOnly;
  }

  @JsonProperty("launchParameters")
  public List<FlowBlueprintLaunchParameter> launchParameters() {
    return launchParameters;
  }

  @JsonProperty("memory")
  public FlowBlueprintMemory memory() {
    return memory;
  }

  @JsonProperty("steps")
  public List<FlowBlueprintStep> steps() {
    return steps;
  }

  public Optional<FlowBlueprintStep> step(String id) {
    return Optional.ofNullable(stepsIndex.get(id));
  }

  @JsonProperty("title")
  public String legacyTitle() {
    return metadata != null ? metadata.title() : null;
  }

  @JsonProperty("description")
  public String legacyDescription() {
    return metadata != null ? metadata.description() : null;
  }

  public Map<String, FlowBlueprintStep> stepsIndex() {
    return stepsIndex;
  }

  @Override
  public String toString() {
    return "FlowBlueprint{schemaVersion="
        + schemaVersion
        + ", startStepId='"
        + startStepId
        + '\''
        + ", steps="
        + steps.size()
        + '}';
  }

  @Override
  public int hashCode() {
    return Objects.hash(schemaVersion, metadata, startStepId, syncOnly, launchParameters, memory, steps);
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) {
      return true;
    }
    if (!(obj instanceof FlowBlueprint other)) {
      return false;
    }
    return Objects.equals(schemaVersion, other.schemaVersion)
        && Objects.equals(metadata, other.metadata)
        && Objects.equals(startStepId, other.startStepId)
        && syncOnly == other.syncOnly
        && Objects.equals(launchParameters, other.launchParameters)
        && Objects.equals(memory, other.memory)
        && Objects.equals(steps, other.steps);
  }
}
