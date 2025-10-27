package com.aiadvent.backend.flow.blueprint;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.ArrayList;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record FlowBlueprintMetadata(String title, String description, List<String> tags) {

  public FlowBlueprintMetadata {
    List<String> safeTags = tags != null ? new ArrayList<>(tags) : new ArrayList<>();
    safeTags.removeIf(item -> item == null || item.isBlank());
    tags = List.copyOf(safeTags);
  }

  public FlowBlueprintMetadata withFallbacks(String legacyTitle, String legacyDescription) {
    String resolvedTitle = title();
    if ((resolvedTitle == null || resolvedTitle.isBlank()) && legacyTitle != null) {
      resolvedTitle = legacyTitle;
    }
    String resolvedDescription = description();
    if ((resolvedDescription == null || resolvedDescription.isBlank()) && legacyDescription != null) {
      resolvedDescription = legacyDescription;
    }
    return new FlowBlueprintMetadata(resolvedTitle, resolvedDescription, tags());
  }

  public static FlowBlueprintMetadata empty() {
    return new FlowBlueprintMetadata(null, null, List.of());
  }

  public static FlowBlueprintMetadata fromLegacy(String title, String description) {
    return new FlowBlueprintMetadata(title, description, List.of());
  }

  public FlowBlueprintMetadata withTitleIfEmpty(String legacyTitle) {
    if (title != null && !title.isBlank()) {
      return this;
    }
    return new FlowBlueprintMetadata(legacyTitle, description, tags);
  }

  public FlowBlueprintMetadata withDescriptionIfEmpty(String legacyDescription) {
    if (description != null && !description.isBlank()) {
      return this;
    }
    return new FlowBlueprintMetadata(title, legacyDescription, tags);
  }

  @Override
  public String title() {
    return title != null && !title.isBlank() ? title : null;
  }

  @Override
  public String description() {
    return description != null && !description.isBlank() ? description : null;
  }

  public List<String> tags() {
    return tags != null ? tags : List.of();
  }
}
