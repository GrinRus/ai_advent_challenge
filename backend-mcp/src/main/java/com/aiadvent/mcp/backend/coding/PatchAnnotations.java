package com.aiadvent.mcp.backend.coding;

import java.util.List;
import java.util.Objects;

/**
 * Describes additional metadata collected for a generated patch. Each field is optional and falls
 * back to an empty list.
 */
public record PatchAnnotations(
    List<String> files, List<String> risks, List<String> conflicts) {

  public PatchAnnotations {
    files = List.copyOf(
        Objects.requireNonNullElse(files, List.of()));
    risks = List.copyOf(
        Objects.requireNonNullElse(risks, List.of()));
    conflicts = List.copyOf(
        Objects.requireNonNullElse(conflicts, List.of()));
  }

  public static PatchAnnotations empty() {
    return new PatchAnnotations(List.of(), List.of(), List.of());
  }
}
